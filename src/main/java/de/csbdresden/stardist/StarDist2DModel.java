package de.csbdresden.stardist;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.scijava.util.FileUtils;

public class StarDist2DModel {
    
    static final String MODEL_DSB2018_HEAVY_AUGMENTATION = "Versatile (fluorescent nuclei)";
    static final String MODEL_DSB2018_PAPER = "DSB 2018 (from StarDist 2D paper)";
    static final String MODEL_DEFAULT = MODEL_DSB2018_HEAVY_AUGMENTATION;    
    
    static final Map<String, StarDist2DModel> MODELS = new LinkedHashMap<String, StarDist2DModel>();
    static {
        MODELS.put(MODEL_DSB2018_PAPER, new StarDist2DModel(StarDist2DModel.class.getClassLoader().getResource("models/2D/dsb2018_paper.zip"), 0.417819, 0.5, 8, 47));
        MODELS.put(MODEL_DSB2018_HEAVY_AUGMENTATION, new StarDist2DModel(StarDist2DModel.class.getClassLoader().getResource("models/2D/dsb2018_heavy_augment.zip"), 0.479071, 0.3, 16, 94));
    }
    
    // -----------
    
    public final URL url;
    public final double probThresh;
    public final double nmsThresh;
    public final int sizeDivBy;
    public final int tileOverlap;
    private final String protocol;
    
    public StarDist2DModel(URL url, double probThresh, double nmsThresh, int sizeDivBy, int tileOverlap) {
        this.url = url;
        this.protocol = url.getProtocol().toLowerCase();
        this.probThresh = probThresh;
        this.nmsThresh = nmsThresh;
        this.sizeDivBy = sizeDivBy;
        this.tileOverlap = tileOverlap;
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
