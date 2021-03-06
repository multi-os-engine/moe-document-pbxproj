/*
Copyright 2014-2016 Intel Corporation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.moe.document.pbxproj;

import org.apache.commons.codec.binary.Hex;
import org.moe.document.pbxproj.Root.RootObjects;
import org.moe.document.pbxproj.nextstep.Dictionary;
import org.moe.document.pbxproj.nextstep.NextStep;
import org.moe.document.pbxproj.nextstep.NextStepException;
import org.moe.document.pbxproj.nextstep.Value;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map.Entry;

@SuppressWarnings("unchecked")
public class ProjectFile {

    private final Root root;
    private final File projectFile;
    private final String projectName;

    private static final Class<? extends PBXObject> promotions[] = new Class[] { PBXBuildFile.class,
            PBXFileReference.class, PBXBuildRule.class, PBXContainerItemProxy.class, PBXCopyFilesBuildPhase.class,
            PBXFrameworksBuildPhase.class, PBXGroup.class, PBXHeadersBuildPhase.class, PBXNativeTarget.class,
            PBXProject.class, PBXReferenceProxy.class, PBXResourcesBuildPhase.class, PBXShellScriptBuildPhase.class,
            PBXSourcesBuildPhase.class, PBXTargetDependency.class, PBXVariantGroup.class, XCBuildConfiguration.class,
            XCConfigurationList.class
    };

    public ProjectFile() {
        this.projectName = null;
        this.projectFile = null;
        this.root = new Root(new Dictionary<Value, NextStep>());

        build();
    }

    public ProjectFile(File file) throws ProjectException {
        if (file == null) {
            throw new IllegalArgumentException("file cannot be null");
        }

        // Fix path if xcodeproj is the input
        int idx = file.getName().lastIndexOf('.');
        if (idx >= 0 && file.getName().substring(idx + 1).equals("xcodeproj")) {
            file = new File(file, "project.pbxproj");
        }

        // Get project name
        File parent = file.getParentFile();
        if (parent != null) {
            String name = parent.getName();
            if (name.endsWith(".xcodeproj")) {
                projectName = name.substring(0, name.length() - ".xcodeproj".length());
            } else {
                projectName = null;
            }
        } else {
            projectName = null;
        }

        // Read file
        Dictionary<Value, NextStep> rootdict;
        try {
            FileInputStream fis;
            try {
                fis = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                throw new NextStepException(e);
            }
            rootdict = NextStep.read(fis);
        } catch (NextStepException e) {
            throw new ProjectException("Failed to parse file!");
        }
        if (rootdict == null) {
            throw new ProjectException("Failed to parse file!");
        }

        // Set root
        this.projectFile = file;
        this.root = new Root(rootdict);

        build();
    }

    public ProjectFile(InputStream stream) throws ProjectException {
        if (stream == null) {
            throw new IllegalArgumentException("stream cannot be null");
        }

        // Get project name
        projectName = null;

        // Read file
        Dictionary<Value, NextStep> rootdict;
        try {
            rootdict = NextStep.read(stream);
        } catch (NextStepException e) {
            throw new ProjectException("Failed to parse file!");
        }
        if (rootdict == null) {
            throw new ProjectException("Failed to parse file!");
        }

        // Set root
        this.projectFile = null;
        this.root = new Root(rootdict);

        build();
    }

    public ProjectFile(String content) throws ProjectException {
        if (content == null) {
            throw new IllegalArgumentException("content cannot be null");
        }

        // Get project name
        projectName = null;

        // Read file
        Dictionary<Value, NextStep> rootdict;
        try {
            rootdict = NextStep.readString(content);
        } catch (NextStepException e) {
            throw new ProjectException("Failed to parse file!");
        }
        if (rootdict == null) {
            throw new ProjectException("Failed to parse file!");
        }

        // Set root
        this.projectFile = null;
        this.root = new Root(rootdict);

        build();
    }

    private final HashMap<String, Value> pbxobjects = new HashMap<String, Value>();

    private void build() {
        final RootObjects objects = getRoot().getObjects();
        Dictionary<Value, NextStep> rawobjects = (Dictionary<Value, NextStep>)getRoot().get("objects");
        if (rawobjects == null) {
            throw new NullPointerException();
        }
        for (Entry<Value, NextStep> field : rawobjects.entrySet()) {
            if (field.getValue() instanceof Dictionary) {
                Dictionary<Value, NextStep> value = (Dictionary<Value, NextStep>)field.getValue();

                Value isa = (Value)value.get(PBXObject.ISA_KEY);
                if (isa != null) {
                    boolean promoted = false;
                    for (Class<? extends PBXObject> cls : promotions) {
                        if (isa.value.equals(cls.getSimpleName())) {
                            try {
                                field.setValue(cls.getConstructor(Dictionary.class).newInstance(value));
                                promoted = true;
                                break;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to instantiate " + cls.getSimpleName(), e);
                            }
                        }
                    }

                    if (!promoted) {
                        field.setValue(new UnknownPBXObject(value));
                    }

                    objects.replaceKey(field, new PBXObjectRef<PBXObject>(field.getKey(), (PBXObject)field.getValue()));
                    pbxobjects.put(field.getKey().value, field.getKey());
                }
            }
        }

        // Connect references
        root.connectReferences(pbxobjects);
        for (Entry<Value, NextStep> field : rawobjects.entrySet()) {
            if (field.getValue() instanceof PBXObject) {
                PBXObject value = (PBXObject)field.getValue();
                value.connectReferences(pbxobjects);
            }
        }

        // Set project name
        PBXObjectRef<PBXProject> proj = root.getRootObject();
        if (proj != null && proj.getReferenced() != null) {
            proj.getReferenced().setProjectName(projectName);
        }

        rawobjects.setCustomPrinter(new Dictionary.FieldPrinter<Value, NextStep>() {

            private Class<? extends PBXObject> lastClass = null;

            @Override
            public void initialize() {
                lastClass = null;
            }

            @Override
            public boolean printField(Dictionary.Field<Value, NextStep> current, boolean hasNext,
                    StringBuilder builder) {
                return false;
            }

            @Override
            public void beforeField(Dictionary.Field<Value, NextStep> current, boolean hasNext, StringBuilder builder) {
                if (current.getValue().getClass() != lastClass) {
                    if (lastClass != null) {
                        builder.append("/* End ");
                        builder.append(lastClass.getSimpleName());
                        builder.append(" section */\n");
                    }

                    lastClass = (Class<? extends PBXObject>)current.getValue().getClass();
                    builder.append("\n/* Begin ");
                    builder.append(lastClass.getSimpleName());
                    builder.append(" section */\n");
                }
            }

            @Override
            public void afterField(Dictionary.Field<Value, NextStep> current, boolean hasNext, StringBuilder builder) {
                if (!hasNext && lastClass != null) {
                    builder.append("/* End ");
                    builder.append(lastClass.getSimpleName());
                    builder.append(" section */\n");
                }
            }
        });
    }

    public void save() throws IOException {
        saveAs(projectFile);
    }

    public void saveAs(File file) throws IOException {
        sortAndUpdate();

        // Fix path if xcodeproj is the input
        int idx = file.getName().lastIndexOf('.');
        if (idx >= 0 && file.getName().substring(idx + 1).equals("xcodeproj")) {
            file = new File(file, "project.pbxproj");
        }

        file.getParentFile().mkdirs();

        FileOutputStream os = new FileOutputStream(file);
        try {
            os.write(getRoot().toString().getBytes());
        } finally {
            os.close();
        }
    }

    private void sortAndUpdate() {
        getRoot().getObjects().sortObjects();
        getRoot().getObjects().updateObjects();
    }

    @Override
    public String toString() {
        sortAndUpdate();
        return getRoot().toString();
    }

    public Root getRoot() {
        return root;
    }

    public File getSourceRoot() {
        if (projectFile != null) {
            return projectFile.getParentFile().getParentFile();
        }
        return null;
    }

    public <T extends PBXObject> PBXObjectRef<T> createReference(T pbxobject) {
        if (pbxobject == null) {
            throw new IllegalArgumentException();
        }

        String uid = generateReference();
        while (root.containsKey(uid)) {
            uid = generateReference();
        }

        return new PBXObjectRef<T>(uid, pbxobject);
    }

    private String generateReference() {
        MessageDigest md;
        SecureRandom prng;
        try {
            md = MessageDigest.getInstance("SHA1");
            prng = SecureRandom.getInstance("SHA1PRNG");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Random generator failed", e);
        }

        String randomNum = Integer.toString(prng.nextInt());
        String ref = new String(Hex.encodeHex(md.digest(randomNum.getBytes())));
        return ref.toUpperCase().substring(0, 24);
    }

    public PBXObjectRef<PBXBuildFile> newPBXBuildFile(PBXObjectRef<? extends PBXObject> fileref) {
        PBXBuildFile object = new PBXBuildFile();
        object.setFileRef(fileref);

        PBXObjectRef<PBXBuildFile> ref = createReference(object);
        getRoot().getObjects().put(ref);

        return ref;
    }

    public PBXObjectRef<PBXFileReference> newPBXFileReference(String explicitFileType, String lastKnownFileType,
            String includeInIndex, String name, String path, String sourceTree) {
        PBXFileReference object = new PBXFileReference();
        object.setExplicitFileType(explicitFileType);
        object.setLastKnownFileType(lastKnownFileType);
        object.setIncludeInIndex(includeInIndex);
        object.setName(name);
        object.setPath(path);
        object.setSourceTree(sourceTree);

        PBXObjectRef<PBXFileReference> ref = createReference(object);
        getRoot().getObjects().put(ref);

        return ref;
    }

    public PBXObjectRef<PBXGroup> newPBXGroup(String name, String path, String sourceTree) {
        PBXGroup object = new PBXGroup();
        object.setName(name);
        object.setPath(path);
        object.setSourceTree(sourceTree);

        PBXObjectRef<PBXGroup> ref = createReference(object);
        getRoot().getObjects().put(ref);

        return ref;
    }

}
