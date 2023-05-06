package io.github.epi155.recfm.exec;

import lombok.extern.slf4j.Slf4j;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * main plugin class
 */
@Slf4j
public class RecordFormatPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RecordFormatExtension extension = project.getExtensions().create("recfm", RecordFormatExtension.class);
        if (extension.getGenerateDirectory() == null)
            extension.setGenerateDirectory(project.getBuildDir() + "/generated-sources/recfm");
        if (extension.getSettingsDirectory() == null)
            extension.setSettingsDirectory(project.getProjectDir() + "/src/main/resources");

        RecordFormatTask task = project.getTasks().create("recfmTask", RecordFormatTask.class);
        project.getTasks().getAt("compileJava").dependsOn(task);

//        project.afterEvaluate(proj -> {
//            SourceSetContainer srcSetCo = proj.getExtensions().getByType(SourceSetContainer.class);
//            SourceSet srcSet = srcSetCo.getByName("main");
//            srcSet.java(it -> it.srcDir(extension.getGenerateDirectory()));
//        });
    }

}
