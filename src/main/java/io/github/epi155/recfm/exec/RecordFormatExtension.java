package io.github.epi155.recfm.exec;

import lombok.Data;

/**
 * plugin parameters
 */
@Data
public class RecordFormatExtension {
    private String generateDirectory; // default: "${project.buildDir}/generated-sources/recfm"
    private String settingsDirectory; // default: "${project.projectDir}/src/main/resources"
    private int align = 4;
    private boolean doc = true;
    private boolean enforceGetter = true;
    private boolean enforceSetter = true;
    private String[] settings;
    private boolean addCompileSourceRoot = true;
    private boolean addTestCompileSourceRoot = false;
    private String codeProviderClassName;
}
