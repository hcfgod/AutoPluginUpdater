package me.bestdad.autoPluginUpdater.service;

import org.apache.maven.artifact.versioning.ComparableVersion;

public final class VersionService {

    public boolean isNewer(String currentVersion, String candidateVersion) {
        return compare(currentVersion, candidateVersion) < 0;
    }

    public boolean isSameVersion(String left, String right) {
        return compare(left, right) == 0;
    }

    public int compare(String left, String right) {
        String normalizedLeft = sanitize(left);
        String normalizedRight = sanitize(right);

        if (normalizedLeft.isEmpty() && normalizedRight.isEmpty()) {
            return 0;
        }
        if (normalizedLeft.isEmpty()) {
            return -1;
        }
        if (normalizedRight.isEmpty()) {
            return 1;
        }

        try {
            ComparableVersion leftVersion = new ComparableVersion(normalizedLeft);
            ComparableVersion rightVersion = new ComparableVersion(normalizedRight);
            return leftVersion.compareTo(rightVersion);
        } catch (RuntimeException ignored) {
            return normalizedLeft.compareToIgnoreCase(normalizedRight);
        }
    }

    private String sanitize(String version) {
        return version == null ? "" : version.trim();
    }
}
