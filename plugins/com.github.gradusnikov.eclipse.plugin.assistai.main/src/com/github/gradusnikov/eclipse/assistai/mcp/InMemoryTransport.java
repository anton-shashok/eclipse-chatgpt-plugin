package com.github.gradusnikov.eclipse.assistai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerSession.Factory;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-memory transport implementation that allows client and server to communicate
 * within the same JVM process. This transport is useful for testing and scenarios where
 * both client and server are running in the same application.
 * 
 * <p>This implementation creates a pair of transports (client and server) that are linked to each other,
 * with messages from one being delivered to the other.</p>
 */
public class InMemoryTransport 
{
    /**
     * Creates a connected pair of client and server transports.
     * 
     * @param objectMapper The ObjectMapper to use for serialization
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair(ObjectMapper objectMapper) {
        // Create message queues for bidirectional communication
        BlockingQueue<McpSchema.JSONRPCMessage> clientToServerQueue = new LinkedBlockingQueue<>();
        BlockingQueue<McpSchema.JSONRPCMessage> serverToClientQueue = new LinkedBlockingQueue<>();
        
        // Create error sinks
        Sinks.Many<String> clientErrorSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> serverErrorSink = Sinks.many().unicast().onBackpressureBuffer();

        // Create the transports
        InMemoryClientTransport clientTransport = new InMemoryClientTransport(
                serverToClientQueue, clientToServerQueue, clientErrorSink, objectMapper);
        
        InMemoryServerTransportProvider serverTransportProvider = new InMemoryServerTransportProvider(
                clientToServerQueue, serverToClientQueue, serverErrorSink, objectMapper);

        return new TransportPair(clientTransport, serverTransportProvider);
    }

    /**
     * Creates a connected pair of client and server transports with a default ObjectMapper.
     * 
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair() {
        return createTransportPair(new ObjectMapper());
    }

    /**
     * A container for the paired client and server transports.
     */
    public static class TransportPair {
        private final McpClientTransport clientTransport;
        private final McpServerTransportProvider serverTransportProvider;

        TransportPair(McpClientTransport clientTransport, McpServerTransportProvider serverTransportProvider) {
            this.clientTransport = clientTransport;
            this.serverTransportProvider =serverTransportProvider;
        }

        public McpClientTransport getClientTransport() {
            return clientTransport;
        }

        public McpServerTransportProvider getServerTransportProvider() {
			return serverTransportProvider;
		}
    }

    /**
     * Implementation of the client side of the in-memory transport.
     */
    private static class InMemoryClientTransport implements McpClientTransport {
        private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
        private final Sinks.Many<String> errorSink;
        private final ObjectMapper objectMapper;
        private volatile boolean isClosing = false;

        InMemoryClientTransport(
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink,
                ObjectMapper objectMapper) {
            
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            Assert.notNull(objectMapper, "ObjectMapper cannot be null");
            
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
            this.objectMapper = objectMapper;
            this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
        }

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return Mono.fromRunnable(() -> {
                // Set up message processing
                setupMessageHandler(handler);
                
                // Start a background thread to poll messages from the inbound queue
                Schedulers.boundedElastic().schedule(() -> {
                    try {
                        while (!isClosing) {
                            McpSchema.JSONRPCMessage message = inboundQueue.take();
                            if (!inboundSink.tryEmitNext(message).isSuccess() && !isClosing) {
//                                logger.error("Failed to emit inbound message: " + message);
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        if (!isClosing) {
//                            logger.error("Interrupted while waiting for messages", e);
                        }
                        Thread.currentThread().interrupt();
                    } finally {
                        if (!isClosing) {
                            isClosing = true;
                            inboundSink.tryEmitComplete();
                        }
                    }
                });
            }).subscribeOn(Schedulers.boundedElastic()).then();
        }

        private void setupMessageHandler(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            this.inboundSink.asFlux()
                .flatMap(message -> {
                    // Process each message and ignore its result (convert to Mono<Void>)
                    return Mono.just(message)
                        .transform(handler)
                        .contextWrite(ctx -> ctx.put("observation", "inMemoryObservation"))
                        .then();  // Convert to Mono<Void>
                })
                .subscribe();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromCallable(() -> {
                if (isClosing) {
                    return false;
                }
                try {
                    outboundQueue.put(message);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            })
            .flatMap(success -> success ? Mono.empty() : Mono.error(new RuntimeException("Failed to enqueue message")))
            .subscribeOn(Schedulers.boundedElastic()).then();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                isClosing = true;
                inboundSink.tryEmitComplete();
                errorSink.tryEmitComplete();
            }).then().subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        public Sinks.Many<String> getErrorSink() {
            return errorSink;
        }
    }

    /**
     * Implementation of the server side of the in-memory transport.
     */
    private static class InMemoryServerTransportProvider implements McpServerTransportProvider {
    	
		private static final Logger logger = LoggerFactory.getLogger(InMemoryServerTransportProvider.class);
    	
    	private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<String> errorSink;
        private final ObjectMapper objectMapper;
        
        InMemoryServerTransportProvider(
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink,
                ObjectMapper objectMapper) {
            
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            Assert.notNull(objectMapper, "ObjectMapper cannot be null");
            
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
            this.objectMapper = objectMapper;
        }
    	
        private McpServerSession session;
    	
		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			if (this.session == null) {
				return Mono.error(new McpError("No session to close"));
			}
			return this.session.sendNotification(method, params)
				.doOnError(e -> logger.error("Failed to send notification: {}", e.getMessage()));
		}

		@Override
		public Mono<Void> closeGracefully() {
			if (this.session == null) {
				return Mono.empty();
			}
			return this.session.closeGracefully();
		}

		@Override
		public void setSessionFactory(Factory sessionFactory) {
			var transport = new InMemoryServerTransport();
			this.session = sessionFactory.create(transport);
			transport.initProcessing();
		}
    	
    
    private class InMemoryServerTransport implements McpServerTransport {
        
		private final AtomicBoolean isStarted = new AtomicBoolean(false);
    	private final AtomicBoolean isClosing = new AtomicBoolean(false);
    	
    	private final Sinks.Many<JSONRPCMessage> inboundSink;
    	
		private Scheduler inboundScheduler;

		
        
        public InMemoryServerTransport() {
			super();
			this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();

			this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
					"inmemory-inbound");
		}

		private void initProcessing() {
			handleIncomingMessages();
			startInboundProcessing();
		}

		private void handleIncomingMessages() {
			this.inboundSink.asFlux().flatMap(message -> session.handle(message)).doOnTerminate(() -> {
				this.inboundScheduler.dispose();
			}).subscribe();
		}

		/**
		 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
		 * Messages are deserialized and passed to the session for handling.
		 */
		private void startInboundProcessing() {
			if (isStarted.compareAndSet(false, true)) {
				this.inboundScheduler.schedule(() -> {
					try {
	                    while (!isClosing.get()) {
	                        McpSchema.JSONRPCMessage message = inboundQueue.take();
	                        if (!inboundSink.tryEmitNext(message).isSuccess() && !isClosing.get()) {
//	                            logger.error("Failed to emit inbound message: {}", message);
	                            break;
	                        }
	                    }
	                } catch (InterruptedException e) {
	                    if (!isClosing.get()) {
//	                        logger.error("Interrupted while waiting for messages", e);
	                    }
	                    Thread.currentThread().interrupt();
	                } finally {
	                    if (!isClosing.get()) {
	                    	isClosing.set(true);
	                    	if (session != null) {
								session.close();
							}
	                        inboundSink.tryEmitComplete();
	                    }
	                }
				});
			}
			
			// Start a background thread to poll messages from the inbound queue
            Schedulers.boundedElastic().schedule(() -> {
                
            });
		}

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromCallable(() -> {
                if (isClosing.get()) {
                    return false;
                }
                try {
                    outboundQueue.put(message);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            })
            .flatMap(success -> success ? Mono.empty() : Mono.error(new RuntimeException("Failed to enqueue message")))
            .subscribeOn(Schedulers.boundedElastic()).then();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
            	isClosing.set(true);
                inboundSink.tryEmitComplete();
                errorSink.tryEmitComplete();
            }).then().subscribeOn(Schedulers.boundedElastic());
        }
        
        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        public Sinks.Many<String> getErrorSink() {
            return errorSink;
        }
    }}
}