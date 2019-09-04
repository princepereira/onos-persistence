/*
 * Copyright 2016-present Open Networking Foundation
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
package org.onosproject.onosjar;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.HasMavenCoordinates;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implementation of a build rule that generates a onosjar.json file for a set
 * of Java sources.
 */
public class OnosJar extends DefaultJavaLibrary
        implements MavenPublishable{

    // Inject the SHA of this rule's jar into the rule key
    private static String RULE_VERSION;
    static {
        URL pluginJarLocation = OnosJar.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA");
            DigestInputStream dis = new DigestInputStream(pluginJarLocation.openStream(), md);
            // Consume the InputStream...
            while (dis.read() != -1);
            RULE_VERSION = String.format("%032x", new BigInteger(1, md.digest()));
        } catch (NoSuchAlgorithmException | IOException e) {
            System.err.println("Failed to compute hash for OnosJar rule");
            RULE_VERSION = "nil";
            //TODO consider bailing here instead
        }
    }
    @AddToRuleKey
    private final String ruleVersion = RULE_VERSION;

    @AddToRuleKey
    final Optional<String> webContext;

    @AddToRuleKey
    final Optional<String> apiTitle;

    @AddToRuleKey
    final Optional<String> apiVersion;

    @AddToRuleKey
    final Optional<String> apiPackage;

    @AddToRuleKey
    final Optional<String> apiDescription;

    @AddToRuleKey
    final Optional<ImmutableSortedMap<String, SourcePath>> includedResources;

    private final ImmutableSortedSet<HasMavenCoordinates> mavenDeps;

    public OnosJar(BuildRuleParams params,
                   SourcePathResolver resolver,
                   Set<? extends SourcePath> srcs,
                   Set<? extends SourcePath> resources,
                   Optional<Path> generatedSourceFolder,
                   Optional<SourcePath> proguardConfig,
                   ImmutableList<String> postprocessClassesCommands,
                   ImmutableSortedSet<BuildRule> exportedDeps,
                   ImmutableSortedSet<BuildRule> providedDeps,
                   SourcePath abiJar, boolean trackClassUsage,
                   ImmutableSet<Path> additionalClasspathEntries,
                   CompileToJarStepFactory compileStepFactory,
                   Optional<Path> resourcesRoot,
                   Optional<SourcePath> manifestFile,
                   Optional<String> mavenCoords,
                   ImmutableSortedSet<BuildTarget> tests,
                   ImmutableSet<Pattern> classesToRemoveFromJar,
                   Optional<String> webContext,
                   Optional<String> apiTitle,
                   Optional<String> apiVersion,
                   Optional<String> apiPackage,
                   Optional<String> apiDescription,
                   Optional<ImmutableSortedMap<String, SourcePath>> includedResources) {
        super(params, resolver, srcs, resources, generatedSourceFolder,
              proguardConfig, postprocessClassesCommands, exportedDeps,
              providedDeps, abiJar, trackClassUsage, additionalClasspathEntries,
              compileStepFactory, resourcesRoot, manifestFile, mavenCoords,
              tests, classesToRemoveFromJar);
        this.webContext = webContext;
        this.apiTitle = apiTitle;
        this.apiVersion = apiVersion;
        this.apiPackage = apiPackage;
        this.apiDescription = apiDescription;
        this.includedResources = includedResources;
        this.mavenDeps = computeMavenDeps();
    }

    private ImmutableSortedSet<HasMavenCoordinates> computeMavenDeps() {
        ImmutableSortedSet.Builder<HasMavenCoordinates> mavenDeps = ImmutableSortedSet.naturalOrder();
        for (JavaLibrary javaLibrary : getTransitiveClasspathDeps()) {
            if (this.equals(javaLibrary)) {
                // no need to include ourself
                continue;
            } else if (HasMavenCoordinates.MAVEN_COORDS_PRESENT_PREDICATE.apply(javaLibrary)) {
                mavenDeps.add(javaLibrary);
                //FIXME BOC do we always want to exclude all of a maven jar's dependencies? probably.
                mavenDeps.addAll(javaLibrary.getTransitiveClasspathDeps());
            }
        }
        return mavenDeps.build();
    }

    @Override
    public Iterable<HasMavenCoordinates> getMavenDeps() {
        return mavenDeps;
    }

    @Override
    public Iterable<BuildRule> getPackagedDependencies() {
        //FIXME this is not supported at the moment
        return ImmutableList.of();
    }

    @Override
    public Optional<Path> getPomTemplate() {
        //FIXME we should consider supporting this
        return Optional.absent();
    }
}
