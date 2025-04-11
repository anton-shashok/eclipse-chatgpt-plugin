package com.github.gradusnikov.eclipse.assistai;

import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.mcp.McpServerPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelListPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.prompts.PromptsPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.repository.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;

public class Activator extends AbstractUIPlugin 
{
    private static Activator plugin = null;
    
    @Override
    public void start(BundleContext context) throws Exception 
    {
        super.start(context);
        plugin = this;


    }
    
    public static Activator getDefault() 
    {
        return plugin;
    }
    
    public PromptsPreferencePresenter getPromptsPreferencePresenter()
    {
        PromptsPreferencePresenter presenter = new PromptsPreferencePresenter( getDefault().getPreferenceStore() );
        return presenter;
    }
    
    public ModelListPreferencePresenter getModelsPreferencePresenter()
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var repository = ContextInjectionFactory.make( ModelApiDescriptorRepository.class, eclipseContext );
        return new ModelListPreferencePresenter( repository );
    }
    

    public McpServerPreferencePresenter getMCPServerPreferencePresenter() 
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var registry = ContextInjectionFactory.make( McpClientRetistry.class, eclipseContext );
        Objects.requireNonNull( registry, "No actual object of class " + McpClientRetistry.class + " found!" );
        
        McpServerPreferencePresenter presneter = new McpServerPreferencePresenter( 
                getDefault().getPreferenceStore(), 
                registry, 
                getLog() );
        return presneter;
    }    
}
