package io.github.epi155.recfm.exec;

import io.github.epi155.recfm.api.CodeProvider;
import io.github.epi155.recfm.type.*;
import io.github.epi155.recfm.util.GenerateArgs;
import io.github.epi155.recfm.util.Tools;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.util.ServiceLoader;

/**
 * plugin recfm task
 */
@Slf4j
public class RecordFormatTask extends DefaultTask {
    private static final String SET_LENGTH = "setLength";
    private static final String GET_LENGTH = "getLength";

    private static final String SET_OFFSET = "setOffset";
    private static final String GET_OFFSET = "getOffset";

    private static final String SET_REDEFINES = "setRedefines";
    private static final String GET_REDEFINES = "getRedefines";
    private static final String PLUGIN_GROUP_ID = "io.github.epi155";
    private static final String PLUGIN_ARTIFACT_ID = "recfm-gradle-plugin";
    private static final String PLUGIN_VERSION = "0.6.0-SNAPSHOT";

    /**
     * Custom tag in yaml
     * @return {@link Yaml} instance
     */
    public static Yaml prepareYaml() {
        val constructor = new Constructor(ClassesDefine.class);
        Representer representer = new Representer(new DumperOptions());

        tuningClassDef(constructor, representer);
        tuningField(constructor, representer, "!Abc", FieldAbc.class);
        tuningField(constructor, representer, "!Num", FieldNum.class);
        tuningField(constructor, representer, "!Cus", FieldCustom.class);
        tuningField(constructor, representer, "!Dom", FieldDomain.class);
        tuningField(constructor, representer, "!Fil", FieldFiller.class);
        tuningField(constructor, representer, "!Val", FieldConstant.class);
        tuningField(constructor, representer, "!Grp", FieldGroup.class);
        tuningField(constructor, representer, "!Occ", FieldOccurs.class);

        return new Yaml(constructor, representer);
    }

    private static void tuningClassDef(Constructor constructor, Representer representer) {
        TypeDescription td = new TypeDescription(ClassDefine.class);
        td.substituteProperty("len", int.class, GET_LENGTH, SET_LENGTH);
        constructor.addTypeDescription(td);
        representer.addTypeDescription(td);
    }

    private static void tuningField(Constructor c, Representer r, String tag, Class<? extends NakedField> f) {
        TypeDescription td = new TypeDescription(f, tag);
        td.substituteProperty("at", int.class, GET_OFFSET, SET_OFFSET);
        td.substituteProperty("len", int.class, GET_LENGTH, SET_LENGTH);
        if (NamedField.class.isAssignableFrom(f)) {
            td.substituteProperty("red", boolean.class, GET_REDEFINES, SET_REDEFINES);
        }
        if (f == FieldOccurs.class)
            td.substituteProperty("x", int.class, "getTimes", "setTimes");
        else if (f == FieldAbc.class) {
            td.substituteProperty("pad", Character.class, "getPadChar", "setPadChar");
            td.substituteProperty("chk", CheckChar.class, "getCheck", "setCheck");
        } else if (f == FieldNum.class)
            td.substituteProperty("num", boolean.class, "getNumericAccess", "setNumericAccess");
        else if (f == FieldConstant.class)
            td.substituteProperty("val", String.class, "getValue", "setValue");
        else if (f == FieldCustom.class) {
            td.substituteProperty("ini", Character.class, "getInitChar", "setInitChar");
            td.substituteProperty("pad", Character.class, "getPadChar", "setPadChar");
            td.substituteProperty("chk", CheckUser.class, "getCheck", "setCheck");
        }
        c.addTypeDescription(td);
        r.addTypeDescription(td);
    }

    @TaskAction
    void task() {
        val project = getProject();
        val extension = (RecordFormatExtension) project.property("recfm");
        String generateDirectory = extension.getGenerateDirectory();
        String settingsDirectory = extension.getSettingsDirectory();
//        if (generateDirectory == null)
//            generateDirectory = project.getBuildDir() + "/generated-sources/recfm";
//        if (settingsDirectory == null)
//            settingsDirectory = project.getProjectDir() + "/src/main/resources";

        log.info("Check for output directory ...");

        Yaml yaml = prepareYaml();

        val args = GenerateArgs.builder()
            .sourceDirectory(new File(generateDirectory))
            .align(extension.getAlign())
            .doc(extension.isDoc())
            .setCheck(extension.isEnforceSetter())
            .getCheck(extension.isEnforceGetter())
            .group(PLUGIN_GROUP_ID)   // groupId ?
            .artifact(PLUGIN_ARTIFACT_ID)   // ArtifactId
            .version(PLUGIN_VERSION)
            .build();

        val driver = getCodeProvider(extension.getCodeProviderClassName());
        log.info("Settings directory: " + settingsDirectory);
        for (String setting : extension.getSettings()) {
            log.info("Generate from " + setting);
            try (InputStream inputStream = new FileInputStream(settingsDirectory + File.separator + setting)) {
                ClassesDefine structs = yaml.load(inputStream);

                String cwd = Tools.makeDirectory(args.sourceDirectory, structs.getPackageName());
                structs.getClasses().
                    forEach(it -> generateClass(it, driver, cwd, structs, args));
            } catch (FileNotFoundException e) {
                log.warn("Setting " + setting + " does not exist, ignored.");
            } catch (Exception e) {
                log.error(e.toString());
                throw new RuntimeException("Failed to execute plugin", e);
            }
        }

        if (extension.isAddCompileSourceRoot()) {
            val srcSetCo = project.getExtensions().getByType(SourceSetContainer.class);
            srcSetCo.getByName("main").java(it -> it.srcDir(extension.getGenerateDirectory()));
        }
        if (extension.isAddTestCompileSourceRoot()) {
            val srcSetCo = project.getExtensions().getByType(SourceSetContainer.class);
            srcSetCo.getByName("test").java(it -> it.srcDir(extension.getGenerateDirectory()));
        }

        log.info("Done.");
    }

    private CodeProvider getCodeProvider(String codeProviderClassName) {
        ServiceLoader<CodeProvider> loader = ServiceLoader.load(CodeProvider.class);
        for (CodeProvider codeProvider : loader) {
            val codeProviderItem = codeProvider.getClass().getName();
            if (codeProviderClassName == null || codeProviderClassName.equals(codeProviderItem)) {
                log.info("Using Code Provider: " + codeProviderItem);
                return codeProvider;
            }
            log.info("Skip Code Provider: " + codeProviderItem);
        }
        if (codeProviderClassName == null) {
            throw new CodeDriverException();
        } else {
            throw new CodeDriverException(codeProviderClassName);
        }
    }

    private void generateClass(ClassDefine struct, CodeProvider driver, String cwd, ClassesDefine structs, GenerateArgs ga) {
        val wrtPackage = structs.getPackageName();
        val defaults = structs.getDefaults();

        log.info("- Prepare class " + struct.getName() + " ...");
        val classFile = driver.fileOf(cwd, struct.getName());

        struct.checkForVoid();

        boolean checkSuccesful = struct.noBadName();
        checkSuccesful &= struct.noDuplicateName(Tools::testCollision);
        checkSuccesful &= struct.noHole();
        checkSuccesful &= struct.noOverlap();
        if (checkSuccesful) {
            try (PrintWriter pw = new PrintWriter(classFile)) {
                log.info("  [####o] Creating ...");
                driver.createClass(pw, wrtPackage, struct, ga, defaults);
                log.info("  [#####] Created.");
            } catch (IOException e) {
                throw new ClassDefineException(e);
            }
        } else {
            throw new ClassDefineException("Class <" + struct.getName() + "> bad defined");
        }
    }

}
