package de.csbdresden;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.scijava.util.FileUtils;

public class StarDistModel {
    
    public final URL url;
    public final double probThresh;
    public final double nmsThresh;
    private final String protocol;
    
    public StarDistModel(URL url, double probThresh, double nmsThresh) {
        this.url = url;
        this.protocol = url.getProtocol().toLowerCase();
        this.probThresh = probThresh;
        this.nmsThresh = nmsThresh;
    }
    
    public boolean canGetFile() {
        return protocol.equals("file") || protocol.equals("jar");
    }

    public boolean isTempFile() {
        return protocol.equals("jar");
    }
    
    public File getFile() throws IOException {
        switch (protocol) {
        case "file":
            return FileUtils.urlToFile(url);
        case "jar":
            final File tmpModelFile = File.createTempFile("stardist_model_", ".zip");
            Files.copy(url.openStream(), tmpModelFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmpModelFile;            
        default:
            return null;
        }
    }
        
}
