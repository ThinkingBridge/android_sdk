/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.editors.color;

import static com.android.ide.eclipse.adt.AdtConstants.EDITORS_NAMESPACE;

import com.android.ide.eclipse.adt.internal.editors.AndroidXmlCommonEditor;
import com.android.ide.eclipse.adt.internal.editors.XmlEditorDelegate;
import com.android.ide.eclipse.adt.internal.editors.descriptors.ElementDescriptor;
import com.android.ide.eclipse.adt.internal.sdk.AndroidTargetData;
import com.android.resources.ResourceFolderType;

import org.eclipse.ui.IFileEditorInput;
import org.eclipse.wst.sse.core.internal.provisional.IStructuredModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Editor for /res/color XML files.
 */
@SuppressWarnings("restriction")
public class ColorEditorDelegate extends XmlEditorDelegate {

    public static class Creator implements IXmlEditorCreator {
        @Override
        @SuppressWarnings("unchecked")
        public ColorEditorDelegate createForFile(
                AndroidXmlCommonEditor delegator,
                IFileEditorInput input,
                ResourceFolderType type) {
            if (ResourceFolderType.COLOR.equals(type)) {
                return new ColorEditorDelegate(delegator);
            }

            return null;
        }
    }

    /**
     * Old standalone-editor ID.
     * Use {@link AndroidXmlCommonEditor#ID} instead.
     */
    public static final String OLD_STANDALONE_EDITOR_ID = EDITORS_NAMESPACE + ".color.ColorEditor"; //$NON-NLS-1$


    public ColorEditorDelegate(AndroidXmlCommonEditor editor) {
        super(editor);
    }

    @Override
    public void createFormPages() {
        /* Disabled for now; doesn't work quite right
        try {
            addPage(new ColorTreePage(this));
        } catch (PartInitException e) {
            AdtPlugin.log(IStatus.ERROR, "Error creating nested page"); //$NON-NLS-1$
            AdtPlugin.getDefault().getLog().log(e.getStatus());
        }
        */
    }

    @Override
    public void xmlModelChanged(Document xmlDoc) {
        // create the ui root node on demand.
        initUiRootNode(false /*force*/);

        Element rootElement = xmlDoc.getDocumentElement();
        getUiRootNode().loadFromXmlNode(rootElement);
    }

    @Override
    public void initUiRootNode(boolean force) {
        // The manifest UI node is always created, even if there's no corresponding XML node.
        if (getUiRootNode() == null || force) {
            ElementDescriptor descriptor;
            AndroidTargetData data = getEditor().getTargetData();
            if (data == null) {
                descriptor = new ColorDescriptors().getDescriptor();
            } else {
                descriptor = data.getColorDescriptors().getDescriptor();
            }
            setUiRootNode(descriptor.createUiNode());
            getUiRootNode().setEditor(getEditor());
            onDescriptorsChanged();
        }
    }

    private void onDescriptorsChanged() {
        IStructuredModel model = getEditor().getModelForRead();
        if (model != null) {
            try {
                Node node = getEditor().getXmlDocument(model).getDocumentElement();
                getUiRootNode().reloadFromXmlNode(node);
            } finally {
                model.releaseFromRead();
            }
        }
    }
}
