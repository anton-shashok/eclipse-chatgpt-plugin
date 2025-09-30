package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import jakarta.inject.Inject;

import java.io.IOException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;

@Creatable
@McpServer(name = "webpage-reader")
public class ReadWebPageMcpServer
{
    @Inject
    private ILog logger;

    @Tool(name="readWebPage", description="Reads the content of the given web site and returns its content as a markdown text.", type="object")
    public String readWebPage(
            @ToolParam(name="url", description="A web site URL", required=true) String url)
    {
        try (WebClient webClient = new WebClient()) {
	        webClient.getOptions().setJavaScriptEnabled(true);
	        webClient.getOptions().setThrowExceptionOnScriptError(false);
	        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
	        HtmlPage page = webClient.getPage(url);
	        return page.asNormalizedText();
        } catch (FailingHttpStatusCodeException | IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException( "Error retrieving web page content: " + url, e );
        }
    }
}
