package de.csbdresden;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.scijava.util.FileUtils;

public class StarDist2DModel {
    
    static final String MODEL_DSB2018_V1 = "DSB 2018 v1 (nuclei, fluorescence microscopy)";
    static final String MODEL_DEFAULT = MODEL_DSB2018_V1;    
    
    static final Map<String, StarDist2DModel> MODELS = new LinkedHashMap<String, StarDist2DModel>();
    static {
        MODELS.put(MODEL_DSB2018_V1, new StarDist2DModel(StarDist2DModel.class.getClassLoader().getResource("models/2D/dsb2018_v1.zip"), 0.417819, 0.5));        
    }
    
    // -----------
    
    public final URL url;
    public final double probThresh;
    public final double nmsThresh;
    private final String protocol;
    
    public StarDist2DModel(URL url, double probThresh, double nmsThresh) {
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
