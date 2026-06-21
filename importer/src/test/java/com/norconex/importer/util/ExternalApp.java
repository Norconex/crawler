/* Copyright 2017-2026 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.importer.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Java;
import org.apache.tools.ant.types.Path;

import com.norconex.commons.lang.exec.SystemCommand;
import com.norconex.commons.lang.map.Properties;

/**
 * Sample external app that reverses word order in lines and if metadata files
 * are provided, also does the same for metadata values.
 * Also prints specific environment variables to STDOUT or STDERR.
 */
public class ExternalApp {

    public static final String ARG_INFILE_CONTENT = "ic";
    public static final String ARG_OUTFILE_CONTENT = "oc";
    public static final String ARG_INFILE_META = "im";
    public static final String ARG_OUTFILE_META = "om";
    public static final String ARG_REFERENCE = "ref";

    public static final String ENV_STDOUT_BEFORE = "stdout_before";
    public static final String ENV_STDOUT_AFTER = "stdout_after";
    public static final String ENV_STDERR_BEFORE = "stderr_before";
    public static final String ENV_STDERR_AFTER = "stderr_after";

    // reverse the word order in each lines
    // if meta files are provided, reverse each values too
    public static void main(String[] args) throws IOException {

        var cmd = parseCommandLineArguments(args);

        File inFileContent = null;
        File outFileContent = null;
        File inFileMeta = null;
        File outFileMeta = null;
        String reference = null;
        if (cmd.hasOption(ARG_INFILE_CONTENT)) {
            inFileContent = new File(cmd.getOptionValue(ARG_INFILE_CONTENT));
        }
        if (cmd.hasOption(ARG_OUTFILE_CONTENT)) {
            outFileContent = new File(cmd.getOptionValue(ARG_OUTFILE_CONTENT));
        }
        if (cmd.hasOption(ARG_INFILE_META)) {
            inFileMeta = new File(cmd.getOptionValue(ARG_INFILE_META));
        }
        if (cmd.hasOption(ARG_OUTFILE_META)) {
            outFileMeta = new File(cmd.getOptionValue(ARG_OUTFILE_META));
        }
        if (cmd.hasOption(ARG_REFERENCE)) {
            reference = cmd.getOptionValue(ARG_REFERENCE);
            System.out.println("reference=" + reference);
        }

        printEnvToStdout(ENV_STDOUT_BEFORE);
        printEnvToStderr(ENV_STDERR_BEFORE);
        var output = getOutputStream(outFileContent);
        try (var input = getInputStream(inFileContent)) {
            var lines =
                    IOUtils.readLines(input, StandardCharsets.UTF_8);
            for (String line : lines) {
                output.write(reverseWords(line).getBytes());
                output.write('\n');
                output.flush();
            }
        }

        printEnvToStdout(ENV_STDOUT_AFTER);
        printEnvToStderr(ENV_STDERR_AFTER);
        System.out.flush();
        System.err.flush();
        if (output != System.out) {
            output.close();
        }

        // handle meta files
        if (inFileMeta != null && outFileMeta != null) {
            var p = new Properties();
            try (Reader r = new FileReader(inFileMeta);
                    Writer w = new FileWriter(outFileMeta)) {
                p.loadFromProperties(r);
                for (Entry<String, List<String>> entry : p.entrySet()) {
                    var values = entry.getValue().toArray(
                            ArrayUtils.EMPTY_STRING_ARRAY);
                    for (var i = 0; i < values.length; i++) {
                        values[i] = reverseWords(values[i]);
                    }
                    p.set(entry.getKey(), values);
                }
                p.storeToProperties(w);
            }
        }
    }

    private static String reverseWords(String str) {
        var words = str.split(" ");
        ArrayUtils.reverse(words);
        return StringUtils.join(words, " ");
    }

    private static void printEnvToStdout(String varName) {
        var var = System.getenv(varName);
        if (StringUtils.isNotBlank(var)) {
            System.out.println(var);
        }
    }

    private static void printEnvToStderr(String varName) {
        var var = System.getenv(varName);
        if (StringUtils.isNotBlank(var)) {
            System.err.println(var);
        }
    }

    private static InputStream getInputStream(File inFile)
            throws FileNotFoundException {
        if (inFile != null) {
            return new FileInputStream(inFile);
        }
        return System.in;
    }

    private static OutputStream getOutputStream(File outFile)
            throws FileNotFoundException {
        if (outFile != null) {
            return new FileOutputStream(outFile);
        }
        return System.out;
    }

    public static SystemCommand newSystemCommand(String args) {
        return new SystemCommand(newCommandLine(args));
    }

    public static String newCommandLine(String args) {
        var project = new Project();
        project.init();
        try {
            var javaTask = new Java();
            javaTask.setTaskName("runjava");
            javaTask.setProject(project);
            javaTask.setFork(true);
            javaTask.setFailonerror(true);
            javaTask.setClassname(ExternalApp.class.getName());
            javaTask.setClasspath(
                    new Path(project, SystemUtils.JAVA_CLASS_PATH));
            var arg = javaTask.getCommandLine().createArgument();
            arg.setPrefix("\"");
            arg.setLine(args);
            arg.setSuffix("\"");

            var cmdArray = javaTask.getCommandLine().getCommandline();
            cmdArray = SystemCommand.escape(cmdArray);

            var cmd = StringUtils.join(cmdArray, " ");
            return fixCommand(cmd);
        } catch (BuildException e) {
            throw e;
        }
    }

    // Fix the command as necessary.
    // Shorten the command by eliminating items we do not need
    // from classpath and using shorter command aliases.  This is necessary
    // to prevent command line length limitation
    // on windows ("The command line is too long.").
    private static String fixCommand(String command) {
        var cmd = command;
        cmd = cmd.replaceFirst(" -classpath ", " -cp ");

        var cp = cmd.replaceFirst(
                ".*\\s+-cp\\s+(.*)\\s+"
                        + ExternalApp.class.getName() + ".*",
                "$1");
        var isQuoted = false;
        if (cp.matches("^\".*\"$")) {
            isQuoted = true;
            cp = StringUtils.strip(cp, "\"");
        }
        var b = new StringBuilder();
        var m = Pattern.compile(".*?([;:]|$)").matcher(cp);
        while (m.find()) {
            var path = m.group();
            if (keepPath(path)) {
                b.append(path);
            }
        }

        cp = b.toString();
        cp = StringUtils.stripEnd(cp, ":;");

        cp += getTestClassPath();

        cp = cp.replace("\\", "\\\\");
        if (isQuoted) {
            cp = "\"" + cp + "\"";
        }
        return cmd.replaceFirst(
                "(.*\\s+-cp\\s+)(.*)(\\s+"
                        + ExternalApp.class.getName() + ".*)",
                "$1" + cp + "$3");
    }

    private static String getTestClassPath() {
        try {
            return File.pathSeparatorChar + new File(
                    ExternalApp.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI())
                                    .getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Could not obtain test classpath.", e);
        }
    }

    private static final String[] KEEPERS = {
            "norconex-importer",
            "norconex-commons-lang",
            "junit",
            "commons-io",
            "log4j",
            "slf4j",
            "ant",
            "commons-cli",
            "commons-lang",
            "commons-beanutils",
            "commons-logging",
            "commons-lang3",
            "commons-text",
            "commons-collections4"
    };

    private static boolean keepPath(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        for (String keeper : KEEPERS) {
            if (path.contains(keeper)) {
                return true;
            }
        }
        return false;
    }

    private static CommandLine parseCommandLineArguments(String[] args) {
        var options = new Options();
        options.addOption(
                ARG_INFILE_CONTENT, true,
                "Input file (default uses STDIN).");
        options.addOption(
                ARG_OUTFILE_CONTENT, true,
                "Output file (default uses STDOUT).");
        options.addOption(
                ARG_INFILE_META, true,
                "Input metadata file (default does not expect metadata).");
        options.addOption(
                ARG_OUTFILE_META, true,
                "Output metadata file (default to STDOUT/STDERR).");
        options.addOption(
                ARG_REFERENCE, true,
                "Document reference.");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("A problem occured while parsing arguments.");
            e.printStackTrace(System.err);
            var formatter = HelpFormatter.builder().get();
            try {
                formatter.printOptions(options);
            } catch (IOException e1) {
                e1.printStackTrace(System.err);
            }
            System.exit(-1);
        }
        return cmd;
    }
}
