package com.ottospo.tripcompanion;

import java.io.File;

final class SavedTrail {
    final String name;
    final String path;
    final String uri;
    final File file;

    SavedTrail(String name, String path, String uri, File file) {
        this.name = name;
        this.path = path;
        this.uri = uri;
        this.file = file;
    }
}
