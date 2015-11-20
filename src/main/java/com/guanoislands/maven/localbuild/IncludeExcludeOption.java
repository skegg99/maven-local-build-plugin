package com.guanoislands.maven.localbuild;

/**
 * @author dbegun
 */
public class IncludeExcludeOption {
    private String groupIdMask;
    private String artifactIdMask;

    public String getGroupIdMask() {
        return groupIdMask;
    }

    public void setGroupIdMask(String groupIdMask) {
        this.groupIdMask = groupIdMask;
    }

    public String getArtifactIdMask() {
        return artifactIdMask;
    }

    public void setArtifactIdMask(String artifactIdMask) {
        this.artifactIdMask = artifactIdMask;
    }
}
