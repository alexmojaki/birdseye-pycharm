/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.CloseTabToolbarAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.*;
import com.intellij.util.ContentsUtil;

import javax.swing.*;
import java.awt.*;


class PanelWithCloseButton extends JPanel implements Disposable {
    private final ContentManager contentManager;
    private final Project project;

    PanelWithCloseButton(Project project, JComponent centerComponent) {
        super(new BorderLayout());
        this.project = project;

        contentManager = MyProjectComponent.getInstance(project).contentManager();

        if (contentManager != null) {
            contentManager.addContentManagerListener(new ContentManagerAdapter() {
                public void contentRemoved(ContentManagerEvent event) {
                    if (event.getContent().getComponent() == PanelWithCloseButton.this) {
                        Disposer.dispose(PanelWithCloseButton.this);
                        contentManager.removeContentManagerListener(this);
                    }
                }
            });
        }

        DefaultActionGroup myToolbarGroup = new DefaultActionGroup(null, false);
        myToolbarGroup.add(new MyCloseAction());

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.FILEHISTORY_VIEW_TOOLBAR, myToolbarGroup, false);
        JComponent component = new JBScrollPane(centerComponent);
        toolbar.setTargetComponent(component);
        for (AnAction action : myToolbarGroup.getChildren(null)) {
            action.registerCustomShortcutSet(action.getShortcutSet(), component);
        }

        myToolbarGroup.add(new OpenSettingsAction());

        add(component, BorderLayout.CENTER);
        add(toolbar.getComponent(), BorderLayout.WEST);
    }

    private class MyCloseAction extends CloseTabToolbarAction {

        public void actionPerformed(AnActionEvent e) {
            Content content = contentManager.getContent(PanelWithCloseButton.this);
            if (content != null) {
                ContentsUtil.closeContentTab(contentManager, content);
                if (content instanceof TabbedContent && ((TabbedContent) content).hasMultipleTabs()) {
                    final TabbedContent tabbedContent = (TabbedContent) content;
                    final JComponent component = content.getComponent();
                    tabbedContent.removeContent(component);
                    contentManager.setSelectedContent(content, true, true);
                } else {
                    contentManager.removeContent(content, true);
                }
            }
        }
    }

    private class OpenSettingsAction extends AnAction {

        OpenSettingsAction() {
            Presentation presentation = getTemplatePresentation();
            presentation.setIcon(AllIcons.General.ProjectSettings);
            presentation.setText("Show birdseye settings for this project");
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            ShowSettingsUtil.getInstance().editConfigurable(project, new MyConfigurable(project));
        }
    }

    @Override
    public void dispose() {

    }
}
