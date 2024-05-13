package org.objectweb.asm.idea.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;

import javax.swing.*;

/**
 * @author Cédric Champeau (pre refactor)
 * @author Thiakil (December 2017)
 */
public class ASMPluginConfigurable implements Configurable{
	
	private       ASMPluginConfiguration configDialog;
	private final ASMPluginComponent     projectComponent;
	
	public ASMPluginConfigurable(final Project project){
		projectComponent = project.getService(ASMPluginComponent.class);
	}
	
	@Override
	@Nls
	public String getDisplayName(){
		return "ASM Bytecode plugin";
	}
	
	@Override
	public String getHelpTopic(){
		return null;
	}
	
	@Override
	public JComponent createComponent(){
		if(configDialog == null) configDialog = new ASMPluginConfiguration();
		return configDialog.getRootPane();
	}
	
	@Override
	public boolean isModified(){
		return configDialog != null && configDialog.isModified(projectComponent);
	}
	
	@Override
	public void apply(){
		if(configDialog != null){
			configDialog.getData(projectComponent);
		}
	}
	
	@Override
	public void reset(){
		if(configDialog != null){
			configDialog.setData(projectComponent);
		}
	}
	
	@Override
	public void disposeUIResources(){
		configDialog = null;
	}
}
