package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.lang.reflect.Field;

public class MyConfigurable implements Configurable {
    private LabeledField urlPanel;
    private LabeledField dbPanel;
    private LabeledField portPanel;
    private ButtonGroup group;
    private MyProjectComponent projectComponent;

    MyConfigurable(Project project) {
        projectComponent = MyProjectComponent.getInstance(project);
    }

    private State state() {
        return projectComponent.state;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "birdseye";
    }


    @Nullable
    @Override
    public JComponent createComponent() {
        JPanel panel = new JBPanel<>();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JRadioButton radio1 = new JRadioButton("Run local server automatically");
        radio1.setActionCommand("run");
        JRadioButton radio2 = new JRadioButton("Connect to external server at: ");
        radio2.setActionCommand("connect");
        group = new ButtonGroup();
        group.add(radio1);
        group.add(radio2);

        panel.add(radio1);

        portPanel = new LabeledField(
                new JNumberTextField(),
                "Port number: ",
                "port",
                8,
                panel);

        dbPanel = new LabeledField(
                new JTextField(),
                "Database URL (leave blank for default): ",
                "dbUrl",
                40,
                panel);

        JLabel statusLabel = new JBLabel();
        JLabel errorLabel = new JBLabel();
        panel.add(statusLabel);
        panel.add(errorLabel);
        JButton restartButton = null;

        if (state().runServer) {
            ProcessMonitor processMonitor = projectComponent.responsibleProcessMonitor();

            boolean running = processMonitor.isRunning();
            statusLabel.setText(running ?
                    "The server is currently running" :
                    "The server is NOT running");
            statusLabel.setIcon(running ?
                    AllIcons.Actions.Execute :
                    AllIcons.Actions.Suspend);
            errorLabel.setText(processMonitor.errorMessage);

            restartButton = new JButton("Restart server", AllIcons.Actions.Restart);
            restartButton.addActionListener(e -> {
                statusLabel.setIcon(AllIcons.Actions.Execute);
                statusLabel.setText("...");
                errorLabel.setText("");
                processMonitor.stop();
                processMonitor.start();
                Timer timer = new Timer(1000, ev -> statusLabel.setText("The server has been restarted"));
                timer.setRepeats(false);
                timer.start();
            });

            panel.add(restartButton);
        }

        panel.add(radio2);

        urlPanel = new LabeledField(
                new JTextField(),
                "Server URL: ",
                "serverUrl",
                40,
                panel);

        JButton finalRestartButton = restartButton;

        ActionListener actionListener = e -> {
            boolean run = runServer();
            portPanel.setEnabled(run);
            dbPanel.setEnabled(run);
            statusLabel.setEnabled(run);
            errorLabel.setEnabled(run);
            if (finalRestartButton != null) {
                finalRestartButton.setEnabled(run);
            }

            urlPanel.setEnabled(!run);
        };

        radio1.addActionListener(actionListener);
        radio2.addActionListener(actionListener);

        (state().runServer ? radio1 : radio2).doClick(0);

        return panel;
    }

    private boolean runServer() {
        return group.getSelection().getActionCommand().equals("run");
    }

    @Override
    public boolean isModified() {
        return (portPanel.isModified() ||
                dbPanel.isModified() ||
                urlPanel.isModified() ||
                runServer() != state().runServer);
    }

    @Override
    public void apply() throws ConfigurationException {
        state().runServer = runServer();
        String port = portPanel.textField.getText();
        ConfigurationException portError = new ConfigurationException(
                "Port number must be an integer between 1024 and 65535");
        try {
            int portNumber = Integer.parseInt(port);
            if (1023 >= portNumber || portNumber >= 65536) {
                throw portError;
            }
        } catch (NumberFormatException e) {
            throw portError;
        }
        portPanel.save();
        dbPanel.save();
        urlPanel.save();
        MyApplicationComponent.getInstance().updateServers();
    }

    class LabeledField extends JPanel {
        private JTextField textField;
        private JLabel label;
        private final String stateFieldName;

        private LabeledField(JTextField textField,
                             String labelText,
                             String stateFieldName,
                             int columns,
                             JPanel panel) {
            this.textField = textField;
            label = new JLabel(labelText);
            this.stateFieldName = stateFieldName;
            add(label);
            add(textField);
            label.setLabelFor(textField);
            setAlignmentX(JComponent.LEFT_ALIGNMENT);

            textField.setText(stateValue());

            textField.setColumns(columns);

            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            textField.setMaximumSize(textField.getPreferredSize());

            panel.add(this);
            setSize(getPreferredSize());
        }

        private Field stateField() throws NoSuchFieldException {
            return state().getClass().getField(stateFieldName);
        }

        private void save() {
            try {
                stateField().set(state(), textField.getText());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private String stateValue() {
            try {
                return (String) stateField().get(state());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            label.setEnabled(enabled);
            textField.setEnabled(enabled);
        }

        boolean isModified() {
            return !stateValue().equals(textField.getText());
        }

    }
}
