package com.guanoislands.maven.localbuild;

import org.apache.commons.io.FileUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.repository.internal.DefaultArtifactDescriptorReader;
import org.apache.maven.repository.internal.DefaultVersionRangeResolver;
import org.apache.maven.repository.internal.DefaultVersionResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemSession;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.providers.file.FileWagon;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.codehaus.plexus.util.CollectionUtils;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.connector.wagon.WagonProvider;
import org.sonatype.aether.connector.wagon.WagonRepositoryConnectorFactory;
import org.sonatype.aether.deployment.DeployRequest;
import org.sonatype.aether.deployment.DeploymentException;
import org.sonatype.aether.graph.DependencyNode;
import org.sonatype.aether.impl.ArtifactDescriptorReader;
import org.sonatype.aether.impl.VersionRangeResolver;
import org.sonatype.aether.impl.VersionResolver;
import org.sonatype.aether.impl.internal.DefaultServiceLocator;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.repository.WorkspaceReader;
import org.sonatype.aether.resolution.ArtifactDescriptorRequest;
import org.sonatype.aether.resolution.ArtifactDescriptorResult;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.ArtifactResult;
import org.sonatype.aether.resolution.MetadataRequest;
import org.sonatype.aether.resolution.MetadataResult;
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.util.artifact.SubArtifact;
import org.sonatype.aether.util.metadata.DefaultMetadata;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;


import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Collects maven dependencies (from private repository) to local repository
 * in order to distribute sources to build with maven
 *
 * @author dbegun
 */
@Mojo(name = "collect",
        requiresDependencyResolution = ResolutionScope.TEST)
public class LocalBuildMojo extends AbstractMojo {
    @Component
    private MavenProject project;

    @Parameter(property = "include", required = true)
    private List<IncludeExcludeOption> includes;

    @Parameter(property = "exclude")
    private List<IncludeExcludeOption> excludes;

    @Parameter(property = "repositoryLayout", defaultValue = "default", required = true)
    private String repositoryLayout;

    @Parameter(property = "targetRepositoryPath")
    private File targetRepositoryPath;

    @Parameter(property = "tempPath", defaultValue = "${project.build.directory}", required = true)
    private String tempDirPath;

    private RepositorySystem localSystem;
    private MavenRepositorySystemSession localSession;

    @Parameter(defaultValue = "${repositorySystemSession}")
    protected RepositorySystemSession repoSession;

    @Component
    protected ProjectDependenciesResolver projectDependenciesResolver;

    private static final String MAVEN_METADATA_XML = "maven-metadata.xml";

    private Set<String> foundParents = new HashSet<String>();

    public void execute() throws MojoExecutionException {
        File tempDir = new File(tempDirPath + "/buildtemp");
        try {
            RemoteRepository targetRepository =
                    new RemoteRepository("local-build-repo", "default", "file://" + targetRepositoryPath);
            initLocalSession(tempDir);

            List<Repository> defaultRepositories = project.getRepositories();

            Set<Artifact> artifacts = new HashSet<Artifact>();
            artifacts.addAll(project.getDependencyArtifacts());
            artifacts.addAll(getDependencyAddArtifacts(project, repoSession, projectDependenciesResolver));


            for (Artifact artifact : artifacts) {
                if (!isAlreadyDeployed(artifact, targetRepository)) {
                    getLog().info(artifact.toString() + " located: " + artifact.getFile().toString() + " << included");

                    deployToTargetRepo(artifact, targetRepository);

                } else {
                    getLog().info(artifact.toString() + " << ignored");
                }
            }
        } finally {
            try {
                FileUtils.forceDelete(tempDir);
            } catch (IOException e) {
                getLog().error("Can't cleanup temp directory on exit");
            }
        }
    }


    public static Set getDependencyAddArtifacts(MavenProject project, RepositorySystemSession repoSession,
                                                ProjectDependenciesResolver projectDependenciesResolver)
            throws MojoExecutionException {

        DefaultDependencyResolutionRequest dependencyResolutionRequest =
                new DefaultDependencyResolutionRequest(project, repoSession);
        DependencyResolutionResult dependencyResolutionResult;

        try {

            dependencyResolutionResult = projectDependenciesResolver.resolve(dependencyResolutionRequest);
        } catch (DependencyResolutionException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }

        Set artifacts = new LinkedHashSet();
        if (dependencyResolutionResult.getDependencyGraph() != null
                && !dependencyResolutionResult.getDependencyGraph().getChildren().isEmpty()) {
            RepositoryUtils.toArtifacts(artifacts, dependencyResolutionResult.getDependencyGraph().getChildren(),
                    Collections.singletonList(project.getArtifact().getId()), null);
        }
        return artifacts;
    }


    private boolean isAlreadyDeployed(Artifact artifact, RemoteRepository targetRepository) {
        ArtifactRequest artifactRequest = new ArtifactRequest();

        org.sonatype.aether.artifact.Artifact aetherArtifact = toArtifact(artifact);
        artifactRequest.setArtifact(aetherArtifact);
        artifactRequest.setRepositories(Arrays.asList(targetRepository));

        ArtifactResult artifactResult = null;
        try {
            artifactResult = localSystem.resolveArtifact(localSession, artifactRequest);
            getLog().info(">>>>resolve>>> " + aetherArtifact.toString() + " >>> " + artifactResult.isResolved() +
                    " to " + artifactResult.getArtifact().getFile().toString());


        } catch (ArtifactResolutionException ex) {
            getLog().info("exception while resolving artifact" + ex);
            return false;
        }
        return artifactResult.isResolved();
    }


    public org.sonatype.aether.artifact.Artifact toArtifact(Artifact artifact) {
        if (artifact == null) {
            return null;
        }

        String version = artifact.getVersion();
        if (version == null && artifact.getVersionRange() != null) {
            version = artifact.getVersionRange().toString();
        }
        Map<String, String> props = null;
        org.sonatype.aether.artifact.Artifact result =
                new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(),
                        artifact.getArtifactHandler().getExtension(), version, props,
                        RepositoryUtils.newArtifactType(artifact.getType(), artifact.getArtifactHandler()));
        return result;
    }

    private Set<String> guessParentArtifactId(String artifactId) {
        Set<String> set = new HashSet<String>(2);
        set.add(artifactId.substring(0, artifactId.lastIndexOf('.')));
        set.add(artifactId.substring(0, artifactId.lastIndexOf('.')) + ".parent");
        return set;
    }

    private org.sonatype.aether.artifact.Artifact guessParentPom(org.sonatype.aether.artifact.Artifact childPom) {
        final List<String> parsedArtefactIds = new ArrayList<String>(3);

        try {

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();

            DefaultHandler handler = new DefaultHandler() {
                boolean isParent = false;
                boolean isParentArtifactId = false;

                public void startElement(String uri, String localName, String qName,
                                         Attributes attributes) throws SAXException {

                    if (qName.equalsIgnoreCase("parent")) {
                        isParent = true;
                    }
                    if (qName.equalsIgnoreCase("artifactId") && isParent) {
                        isParentArtifactId = true;
                    }
                }

                public void characters(char ch[], int start, int length) throws SAXException {
                    if (isParentArtifactId) {
                        parsedArtefactIds.add(new String(ch, start, length));
                    }
                }

                public void endElement(String uri, String localName,
                                       String qName) throws SAXException {
                    if (qName.equalsIgnoreCase("parent")) {
                        isParent = false;
                    }
                    if (qName.equalsIgnoreCase("artifactId")) {
                        isParentArtifactId = false;
                    }
                }

            };
            File pomfile = childPom.getFile();
            saxParser.parse(pomfile, handler);

        } catch (Exception ex) {
            //nothing
        }

        try {
            Set<String> setOfArtifact = new HashSet<String>();
            String parsedArtifactId = parsedArtefactIds.get(0);
            String generated = childPom.getGroupId() + "." + parsedArtifactId + "." + childPom.getBaseVersion();
            if (parsedArtifactId != null && parsedArtifactId != "" && !foundParents.contains(generated)) {
                setOfArtifact.add(parsedArtifactId);
                foundParents.add(generated);
            } else {
                return null;
            }
            for (String artifactId : setOfArtifact) {

                org.sonatype.aether.artifact.Artifact parentPomArtifact =
                        new DefaultArtifact(childPom.getGroupId(), artifactId, "", "pom",
                                childPom.getBaseVersion());
                try {
                    ArtifactRequest request =
                            new ArtifactRequest(parentPomArtifact, project.getRemoteProjectRepositories(), null);
                    parentPomArtifact = localSystem.resolveArtifact(localSession, request).getArtifact();
                } catch (ArtifactResolutionException ex) {
                    // could not find
                }
                if (parentPomArtifact != null && parentPomArtifact.getFile() != null) {
                    return parentPomArtifact;
                }
            }
        } catch (Exception ex) {
            // nothing
        }
        return null;
    }

    public org.sonatype.aether.artifact.Artifact resolvePom(Artifact artifact)
            throws UnresolvableModelException {
        org.sonatype.aether.artifact.Artifact pomArtifact =
                new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "", "pom",
                        artifact.getBaseVersion());
        try {
            ArtifactRequest request = new ArtifactRequest(pomArtifact, project.getRemoteProjectRepositories(), null);
            pomArtifact = localSystem.resolveArtifact(localSession, request).getArtifact();
        } catch (ArtifactResolutionException ex) {
            throw new UnresolvableModelException(ex.getMessage(), artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getVersion(), ex);
        }
        return pomArtifact;
    }

    private List<MetadataResult> getMeta(Artifact artifact) {

        org.sonatype.aether.artifact.Artifact pomArtifact =
                new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "",
                        artifact.getArtifactHandler().getExtension(), artifact.getVersion());
        ArtifactRequest request = new ArtifactRequest(pomArtifact, project.getRemoteProjectRepositories(), null);

        Metadata metadata =
                new DefaultMetadata(request.getArtifact().getGroupId(), request.getArtifact().getArtifactId(),
                        artifact.getBaseVersion(),
                        MAVEN_METADATA_XML, Metadata.Nature.RELEASE_OR_SNAPSHOT);


        List<MetadataRequest> metadataRequests = new ArrayList<MetadataRequest>(request.getRepositories().size());

        metadataRequests.add(new MetadataRequest(metadata, null, request.getRequestContext()));

        for (RemoteRepository repository : project.getRemoteProjectRepositories()) {
            MetadataRequest metadataRequest = new MetadataRequest(metadata, repository, request.getRequestContext());
            metadataRequest.setDeleteLocalCopyIfMissing(true);
            metadataRequests.add(metadataRequest);
        }

        List<MetadataResult> list = localSystem.resolveMetadata(localSession, metadataRequests);

        return list;
    }

    private void deployParentPom(RemoteRepository targetRepository, org.sonatype.aether.artifact.Artifact pom) {
        DeployRequest deployRequestParent = new DeployRequest();
        deployRequestParent.setRepository(targetRepository);

        org.sonatype.aether.artifact.Artifact parentPom = guessParentPom(pom);
        if (parentPom != null) {
            deployRequestParent.addArtifact(parentPom);

            Metadata meta =
                    new DefaultMetadata(parentPom.getGroupId(), parentPom.getArtifactId(), parentPom.getBaseVersion(),
                            MAVEN_METADATA_XML, Metadata.Nature.RELEASE_OR_SNAPSHOT);


            ArtifactRequest request = new ArtifactRequest(parentPom, project.getRemoteProjectRepositories(), null);
            List<MetadataRequest> metadataRequests = new ArrayList<MetadataRequest>(request.getRepositories().size());

            for (RemoteRepository repository : project.getRemoteProjectRepositories()) {
                MetadataRequest metadataRequest = new MetadataRequest(meta, repository, request.getRequestContext());
                metadataRequests.add(metadataRequest);
            }

            List<MetadataResult> list = localSystem.resolveMetadata(localSession, metadataRequests);

            for (MetadataResult result : list) {
                if (result != null && result.getMetadata() != null) {
                    deployRequestParent.addMetadata(result.getMetadata());
                }
            }

            if (!deployRequestParent.getArtifacts().isEmpty() || !deployRequestParent.getMetadata().isEmpty()) {
                try {
                    localSystem.deploy(localSession, deployRequestParent);
                } catch (DeploymentException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void deployToTargetRepo(Artifact artifact, RemoteRepository targetRepository) {
        DeployRequest deployRequest = new DeployRequest();
        deployRequest.addArtifact(RepositoryUtils.toArtifact(artifact));
        deployRequest.setRepository(targetRepository);

        org.sonatype.aether.artifact.Artifact pom = null;
        try {
            pom = resolvePom(artifact);
        } catch (UnresolvableModelException e) {
            e.printStackTrace();
        }

        deployRequest.addArtifact(pom);

        for (MetadataResult metaResult : getMeta(artifact)) {
            deployRequest.addMetadata(metaResult.getMetadata());
        }

        try {
            localSystem.deploy(localSession, deployRequest);

        } catch (DeploymentException e) {
            getLog().info("can't deploy to target repo " + e);
        }

        deployParentPom(targetRepository, pom);
    }

    private void initLocalSession(File basedir) {
        DefaultServiceLocator locator = new DefaultServiceLocator();
        locator.addService(VersionResolver.class, DefaultVersionResolver.class);
        locator.addService(ArtifactDescriptorReader.class, DefaultArtifactDescriptorReader.class);
        locator.addService(VersionRangeResolver.class, DefaultVersionRangeResolver.class);
        locator.setServices(WagonProvider.class, new ManualWagonProvider());
        locator.addService(RepositoryConnectorFactory.class, WagonRepositoryConnectorFactory.class);
        localSystem = locator.getService(RepositorySystem.class);

        localSession = new MavenRepositorySystemSession();
        LocalRepository localRepo = new LocalRepository(basedir);
        localSession.setLocalRepositoryManager(localSystem.newLocalRepositoryManager(localRepo));
    }

    public static boolean wildCardMatch(String text, String pattern) {
        String[] cards = pattern.split("\\*");
        for (String card : cards) {
            int idx = text.indexOf(card);
            if (idx == -1) {
                return false;
            }
            text = text.substring(idx + card.length());
        }
        return true;
    }

    class ManualWagonProvider implements WagonProvider {
        public Wagon lookup(String roleHint) throws Exception {
            if ("file".equals(roleHint)) {
                return new FileWagon();
            } else if (roleHint != null && roleHint.startsWith("http")) {
                return new HttpWagon();
            }
            return null;
        }
        public void release(Wagon wagon) {
            // nothing
        }
    }
}