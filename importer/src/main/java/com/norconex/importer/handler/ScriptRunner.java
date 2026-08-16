/* Copyright 2015-2024 Norconex Inc.
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
package com.norconex.importer.handler;

import java.util.function.Consumer;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.EqualsUtil;
import com.norconex.importer.handler.condition.impl.ScriptCondition;
import com.norconex.importer.handler.transformer.impl.ScriptTransformer;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Runs scripts written in supported programming languages.
 * Any objects can be bound to instances of this class to reference
 * them or modify them.
 * </p>
 *
 * <h2>About Return Values</h2>
 * <p>
 * In addition to being able to modify bound objects, it is not uncommon to
 * expect a script to return a value. Unfortunately, script engine
 * implementations have different level of support for returning values.
 * To get around this, all script engines provided
 * by the Importer will always check first for the presence of a
 * variable called <code>returnValue</code> (case-sensitive). When set,
 * it will be considered to be your script output, regardless whether
 * you have a return statement or not.
 * If you do not set a <code>returnValue</code> variable, behavior
 * is specific to each engine.
 * </p>
 *
 * <h2>Provided Scripting Languages</h2>
 * <p>
 * At a minimum the Importer supports the following languages and versions
 * out-of-the-box (as of this writing, see Importer release notes for updates).
 * Replace "xyz" to any value/object/variable being returned.
 * </p>
 *
 * <table border="1">
 *   <caption>Supported scripting engines</caption>
 *   <tr>
 *     <th>Engine Name</th>
 *     <th>Language Version</th>
 *     <th>Returning a Value E.g.</th>
 *     <th>Documentation</th>
 *     <th>Notes</th>
 *   </tr>
 *
 *   <tr>
 *     <td>JavaScript</td>
 *     <td>ECMAScript 2022 (ES13)</td>
 *     <td>
 *       <pre>returnValue = xyz;</pre>
 *       If <code>returnValue</code> is not set, the last (unscoped) variable
 *       assigned is automatically returned.
 *     </td>
 *     <td>
 *       <a href="https://262.ecma-international.org/13.0/">
 *       Language Specification</a>
 *     </td>
 *     <td><a href="https://www.graalvm.org/">GraalVM implementation.</a></td>
 *   </tr>
 *
 *   <tr>
 *     <td>lua</td>
 *     <td>Lua 5.2</td>
 *     <td>
 *       <pre>returnValue = xyz;</pre>
 *       If <code>returnValue</code> is not set (unscoped), an normal
 *       Lua <code>return</code> statement can be used instead.
 *     </td>
 *     <td><a href="http://www.lua.org/manual/5.2/">Reference manual</a></td>
 *     <td>
 *       <a href="https://sourceforge.net/projects/luaj/">Luaj project</a>
 *     </td>
 *   </tr>
 *
 *   <tr>
 *     <td>python</td>
 *     <td>Python 2.7</td>
 *     <td>
 *       <pre>returnValue = xyz</pre>
 *     </td>
 *     <td><a href="https://docs.python.org/2.7/reference/">Language reference</a></td>
 *     <td><a href="https://www.jython.org/">Jython implementation</a></td>
 *   </tr>
 *
 *   <tr>
 *     <td>velocity</td>
 *     <td>Apache Velocity 2.3</td>
 *     <td>
 *       <pre>#set(#returnValue = $xyz)</pre>
 *     </td>
 *     <td><a href="https://velocity.apache.org/engine/2.3/vtl-reference.html">
 *         VTL Reference</a></td>
 *     <td></td>
 *   </tr>
 * </table>
 *
 * <h2>Escaping</h2>
 * <p>
 * When using scripting as part of an XML configuration parsed by the crawler
 * (typical command-line usage), you may need to escape certain characters.
 * The main reason being before your script get interpreted, the configuration
 * is first parsed by Apache Velocity. As an example, the dollar sign is
 * used to prefix Velocity variables.  So if your script is also velocity,
 * you will need to prefix the dollar sign with a backslash (e.g.,
 * <code>\${myvar}</code>).
 * </p>
 *
 * <h2>Adding Additional Scripting Languages</h2>
 * <p>
 * You can add support for your favorite scripting language, as long as
 * there is there is a Java "Script Engine" for it, implementing the
 * <a href="https://jcp.org/en/jsr/detail?id=223">JSR 223</a> API
 * specification.
 * </p>
 * <p>
 * Several third-party script engines already exist to support additional
 * languages such as Groovy, JRuby, Scala, etc. Refer to
 * appropriate third-party documentation about these languages to find
 * out how to use them.
 * </p><p>
 * <b>Note:</b> While using a scripting language can be very convenient, it
 * can make your setup harder to maintain by requiring programming knowledge
 * not everybody has.  It should only be considered by experimented teams
 * or adventurous users. <b>Use at your own risk.</b>
 * </p>
 *
 * @param <T> The evaluation response type.
 * @see ScriptCondition
 * @see ScriptTransformer
 */
@EqualsAndHashCode
@ToString
@Slf4j
public class ScriptRunner<T> {

    //MAYBE: add more (e.g., PHP, Phython, Groovy, etc.)
    //TODO maybe log scripting languages dependency versions when
    // scripting is used

    public static final String RETURN_VALUE_VAR_NAME = "returnValue";

    public static final String JAVASCRIPT_ENGINE = "JavaScript";
    public static final String LUA_ENGINE = "lua";
    public static final String VELOCITY_ENGINE = "velocity";

    @Getter
    private final String engineName;
    @Getter
    private final String script;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ScriptEngine engine;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final CompiledScript compiledScript;

    public ScriptRunner(
            @NonNull String engineName,
            @NonNull String script) {
        this(createEngine(engineName), engineName, script);
    }

    private ScriptRunner(
            ScriptEngine engine, String engineName, String script) {
        this.engine = engine;
        this.engineName = engineName;
        this.script = script;
        compiledScript = compileScript(engine, script);
    }

    public ScriptRunner<T> withScript(String script) {
        return new ScriptRunner<>(engine, engineName, script);
    }

    @SuppressWarnings("unchecked")
    public T eval(Consumer<Bindings> binder) throws DocHandlerException {
        var bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        if (binder != null) {
            binder.accept(bindings);
        }
        try {
            Object scriptOutput;
            if (compiledScript != null) {
                scriptOutput = compiledScript.eval(bindings);
            } else {
                scriptOutput = engine.eval(script, bindings);
            }

            var returnValue = engine.get(RETURN_VALUE_VAR_NAME);

            // NOTE: Python does not support returning last assigned value or
            // a return statement outside a function.
            if (returnValue == null
                    && EqualsUtil.equalsAnyIgnoreCase(
                            engineName, JAVASCRIPT_ENGINE, LUA_ENGINE)) {
                returnValue = scriptOutput;
            }

            return (T) returnValue;
        } catch (ScriptException e) {
            throw new DocHandlerException("Script execution error.", e);
        }
    }

    private static ScriptEngine createEngine(String engineName) {
        ScriptEngine engine;
        if (JAVASCRIPT_ENGINE.equalsIgnoreCase(engineName)) {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        }
        engine = new ScriptEngineManager().getEngineByName(engineName);
        if (engine == null) {
            throwInvalidEngine(engineName);
        } else if (JAVASCRIPT_ENGINE.equalsIgnoreCase(engineName)) {
            // Setting bindings here is a GraalVM hack for being able to set
            // configuration options on the ScriptEngine. The options below
            // need to be set once before compile happens to avoid
            // an exception being thrown.
            var bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.put("polyglot.js.allowHostAccess", true);
            bindings.put("polyglot.js.allowHostClassLookup", true);
        }
        return engine;
    }

    private static CompiledScript compileScript(
            ScriptEngine engine, String script) {
        if (engine instanceof Compilable compileEngine) {
            try {
                return compileEngine.compile(script);
            } catch (ScriptException e) {
                throw new IllegalArgumentException(
                        "Invalid script argument. Could not compile. Possibly "
                                + "a syntax error.",
                        e);
            }
        }
        return null;
    }

    private static void throwInvalidEngine(String name) {
        var b = new StringBuilder();
        var mgr = new ScriptEngineManager();
        var factories = mgr.getEngineFactories();
        for (ScriptEngineFactory factory : factories) {
            var engName = factory.getEngineName();
            var engVersion = factory.getEngineVersion();
            var langName = factory.getLanguageName();
            var langVersion = factory.getLanguageVersion();
            b.append("\n\tScript Engine: ");
            b.append(engName);
            b.append(" (" + engVersion + ")\n");
            b.append("\t      Aliases: ");
            b.append(StringUtils.join(factory.getNames(), ", "));
            b.append("\n\t     Language: ");
            b.append(langName);
            b.append(" (" + langVersion + ")");
        }
        LOG.error(
                "Invalid Script Engine \"{}\". "
                        + "Detected Script Engines are:\n{}",
                name, b.toString());
        throw new IllegalArgumentException(
                "No JSR 223 Script Engine found matching the "
                        + "name \"" + name + "\".");
    }
}
