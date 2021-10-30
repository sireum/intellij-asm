package org.objectweb.asm.idea.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nls;

import javax.swing.*;

/**
 * @author Cédric Champeau (pre refactor)
 * @author Thiakil (December 2017)
 */
public class ASMPluginConfigurable implements Configurable {

    private ASMPluginConfiguration configDialog;
    private Project project;
    private ASMPluginComponent projectComponent;

    public ASMPluginConfigurable(final Project project) {
        this.project = project;
        projectComponent = project.getService(ASMPluginComponent.class);
    }

    @Override
    @Nls
    public String getDisplayName() {
        return "ASM Bytecode plugin";
    }

    public Icon getIcon() {
        return IconLoader.getIcon("/images/asm.gif");
    }

    @Override
    public String getHelpTopic() {
        return null;
    }

    @Override
    public JComponent createComponent() {
        if (configDialog==null) configDialog = new ASMPluginConfiguration();
        return configDialog.getRootPane();
    }

    @Override
    public boolean isModified() {
        return configDialog!=null && configDialog.isModified(projectComponent);
    }

    @Override
    public void apply() throws ConfigurationException {
        if (configDialog!=null) {
            configDialog.getData(projectComponent);
        }
    }

    @Override
    public void reset() {
        if (configDialog!=null) {
            configDialog.setData(projectComponent);
        }
    }

    @Override
    public void disposeUIResources() {
        configDialog = null;
    }
}
