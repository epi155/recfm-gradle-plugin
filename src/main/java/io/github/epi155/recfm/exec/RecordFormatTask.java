package io.github.epi155.recfm.exec;

import io.github.epi155.recfm.api.CodeProvider;
import io.github.epi155.recfm.type.*;
import io.github.epi155.recfm.util.GenerateArgs;
import io.github.epi155.recfm.util.Tools;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    
    private static final String GET_CHECK = "getCheck";
    private static final String SET_CHECK = "setCheck";
    
    private static final Pattern pattern = Pattern.compile("^\\s*#!import\\s+(\\S+)\\s*$");
    private static final String PLUGIN_GROUP_ID = "io.github.epi155";
    private static final String PLUGIN_ARTIFACT_ID = "recfm-gradle-plugin";
    private RecordFormatExtension extension;

    /**
     * Custom tag in yaml
     * @return {@link Yaml} instance
     */
    public static Yaml prepareYaml() {
        val constructor = new Constructor(ClassesDefine.class, new LoaderOptions());
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
        tuningField(constructor, representer, "!GRP", FieldGroupProxy.class);
        tuningField(constructor, representer, "!OCC", FieldOccursProxy.class);

        return new Yaml(constructor, representer);
    }

    private static void tuningField(Constructor c, Representer r, String tag, Class<? extends NakedField> f) {
        TypeDescription td = new TypeDescription(f, tag);
        td.substituteProperty("at", int.class, GET_OFFSET, SET_OFFSET);
        td.substituteProperty("len", int.class, GET_LENGTH, SET_LENGTH);
        if (NamedField.class.isAssignableFrom(f)) {
            td.substituteProperty("red", boolean.class, GET_REDEFINES, SET_REDEFINES);
        }
        if (FloatingField.class.isAssignableFrom(f)) {
            td.substituteProperty("ovf", OverflowAction.class, "getOnOverflow", "setOnOverflow");
            td.substituteProperty("unf", UnderflowAction.class, "getOnUnderflow", "setOnUnderflow");
        }
        if (f == FieldOccurs.class)
            td.substituteProperty("x", int.class, "getTimes", "setTimes");
        else if (f == FieldAbc.class) {
            td.substituteProperty("pad", Character.class, "getPadChar", "setPadChar");
            td.substituteProperty("chk", CheckChar.class, GET_CHECK, SET_CHECK);
        } else if (f == FieldNum.class)
            td.substituteProperty("num", boolean.class, "getNumericAccess", "setNumericAccess");
        else if (f == FieldConstant.class)
            td.substituteProperty("val", String.class, "getValue", "setValue");
        else if (f == FieldFiller.class) {
            td.substituteProperty("chr", String.class, "getFillChar", "setFillChar");
            td.substituteProperty("chk", CheckChar.class, GET_CHECK, SET_CHECK);
        } else if (f == FieldCustom.class) {
            td.substituteProperty("ini", Character.class, "getInitChar", "setInitChar");
            td.substituteProperty("pad", Character.class, "getPadChar", "setPadChar");
            td.substituteProperty("chk", CheckUser.class, GET_CHECK, SET_CHECK);
        } else if (f == FieldGroupProxy.class) {
            td.substituteProperty("as", ClassDefine.class, "getTypeDef", "setTypeDef");
        } else if (f == FieldOccursProxy.class) {
            td.substituteProperty("as", ClassDefine.class, "getTypeDef", "setTypeDef");
            td.substituteProperty("x", int.class, "getTimes", "setTimes");
        }
        c.addTypeDescription(td);
        r.addTypeDescription(td);
    }

    private static void tuningClassDef(Constructor constructor, Representer representer) {
        TypeDescription td = new TypeDescription(ClassDefine.class);
        td.substituteProperty("len", int.class, GET_LENGTH, SET_LENGTH);
        constructor.addTypeDescription(td);
        representer.addTypeDescription(td);
    }

    @TaskAction
    void task() {
        val project = getProject();
        this.extension = (RecordFormatExtension) project.property("recfm");
        String generateDirectory = extension.getGenerateDirectory();
        String settingsDirectory = extension.getSettingsDirectory();

        log.info("Check for output directory ...");
        String pluginVersion = forClass(this.getClass());

        Yaml yaml = prepareYaml();

        val args = GenerateArgs.builder()
            .sourceDirectory(new File(generateDirectory))
            .align(extension.getAlign())
            .doc(extension.isDoc())
            .setCheck(extension.isEnforceSetter())
            .getCheck(extension.isEnforceGetter())
            .group(PLUGIN_GROUP_ID)   // groupId ?
            .artifact(PLUGIN_ARTIFACT_ID)   // ArtifactId
            .version(pluginVersion)
            .build();

        val driver = getCodeProvider(extension.getCodeProviderClassName());
        log.info("Settings directory: " + settingsDirectory);
        for (String setting : extension.getSettings()) {
            log.info("Generate from " + setting);
            File configFile = new File(settingsDirectory + File.separator + setting);
            if (!configFile.exists()) {
                log.warn("Setting " + setting + " does not exist, ignored.");
                continue;
            }
            try {
                if (extension.isPreprocessor()) {
                    configFile = preprocess(configFile);
                }
                try (InputStream inputStream = Files.newInputStream(configFile.toPath())) {
                    ClassesDefine structs = yaml.load(inputStream);

                    String cwd = Tools.makeDirectory(args.sourceDirectory, structs.getPackageName());
                    structs.getInterfaces().forEach(it -> generateTrait(it, driver, structs, args));
                    structs.getClasses().forEach(it -> generateClass(it, driver, structs, args));

                }
            } catch (Exception e) {
                log.error(e.toString());
                throw new RuntimeException("Failed to execute plugin", e);
            }
        }
        setupGradlePaths(project, extension);

        log.info("Done.");
    }

    private File preprocess(File configFile) throws IOException {
        File tempConfig = File.createTempFile("recfm-", ".yaml");
        tempConfig.deleteOnExit();
        try (BufferedWriter bw = Files.newBufferedWriter(tempConfig.toPath())) {
            append(bw, configFile);
        }
        return tempConfig;
    }

    private void append(BufferedWriter bw, File configFile) throws IOException {
        try(BufferedReader br = Files.newBufferedReader(configFile.toPath())) {
            String line;
            while((line = br.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if(matcher.matches()) {
                    String inside = matcher.group(1);
                    File insideConf = inside.startsWith(File.separator) ?
                            new File(inside) : new File(extension.getSettingsDirectory() + File.separator + inside);
                    if (insideConf.exists()) {
                        log.info("<< import "+inside);
                        append(bw, insideConf);
                    } else {
                        log.warn("Skip import "+insideConf.getAbsolutePath());
                    }
                } else {
                    bw.write(line);
                    bw.newLine();
                }
            }
        }
    }

    private void generateTrait(ClassDefine trait, CodeProvider driver, ClassesDefine structs, GenerateArgs ga) {
        if (trait.getFields().isEmpty()) return;
        val base = trait.getFields().get(0).getOffset();

        val namespace = structs.getPackageName();
        log.info("- Prepare interface " + trait.getName() + " ...");

        trait.checkForVoid();
        boolean checkSuccesful = trait.noBadName();
        checkSuccesful &= trait.noDuplicateName(Tools::testCollision);
        checkSuccesful &= trait.noHole(base);
        checkSuccesful &= trait.noOverlap(base);
        if (checkSuccesful) {
            log.info("  [####o] Creating ...");
            driver.createInterface(namespace, trait, ga);
            log.info("  [#####] Created.");
        } else {
            throw new ClassDefineException("Class <" + trait.getName() + "> bad defined");
        }
    }

    private void generateClass(ClassDefine clazz, CodeProvider driver, ClassesDefine structs, GenerateArgs ga) {
        val namespace = structs.getPackageName();
        val defaults = structs.getDefaults();

        log.info("- Prepare class " + clazz.getName() + " ...");

        clazz.checkForVoid();

        boolean checkSuccesful = clazz.noBadName();
        checkSuccesful &= clazz.noDuplicateName(Tools::testCollision);
        checkSuccesful &= clazz.noHole();
        checkSuccesful &= clazz.noOverlap();
        if (checkSuccesful) {
            log.info("  [####o] Creating ...");
            driver.createClass(namespace, clazz, ga, defaults);
            log.info("  [#####] Created.");
        } else {
            throw new ClassDefineException("Class <" + clazz.getName() + "> bad defined");
        }
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

    /**
     * Return the version information for the provided {@link Class}.
     * @param cls the Class to retrieve the version for
     * @return the version, or {@code null} if a version can not be extracted
     */
    public static String forClass(Class<?> cls) {
        String implementationVersion = cls.getPackage().getImplementationVersion();
        if (implementationVersion != null) {
            return implementationVersion;
        }
        URL codeSourceLocation = cls.getProtectionDomain().getCodeSource().getLocation();
        try {
            URLConnection connection = codeSourceLocation.openConnection();
            if (connection instanceof JarURLConnection) {
                JarURLConnection jarURLConnection = (JarURLConnection) connection;
                return getImplementationVersion(jarURLConnection.getJarFile());
            }
            try (JarFile jarFile = new JarFile(new File(codeSourceLocation.toURI()))) {
                return getImplementationVersion(jarFile);
            }
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static String getImplementationVersion(JarFile jarFile) throws IOException {
        return jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
    }

    private void setupGradlePaths(Project project, RecordFormatExtension extension) {
        String generateDirectory = extension.getGenerateDirectory();
        if (extension.isAddCompileSourceRoot()) {
            log.info("Add {} to main SourceSet", generateDirectory);
            val srcSetCo = project.getExtensions().getByType(SourceSetContainer.class);
            srcSetCo.getByName("main").java(it -> it.srcDir(generateDirectory));
        }
        if (extension.isAddTestCompileSourceRoot()) {
            log.info("Add {} to test SourceSet", generateDirectory);
            val srcSetCo = project.getExtensions().getByType(SourceSetContainer.class);
            srcSetCo.getByName("test").java(it -> it.srcDir(generateDirectory));
        }
    }

}
