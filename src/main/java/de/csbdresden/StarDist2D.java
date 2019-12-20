package de.csbdresden;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;

import de.csbdresden.csbdeep.commands.GenericNetwork;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > StarDist 2D")
public class StarDist2D implements Command {
    
    private static final String MODEL_FILE       = "Model (.zip) from File";
    private static final String MODEL_URL        = "Model (.zip) from URL";
    private static final String MODEL_DSB2018_V1 = "DSB 2018 v1 (nuclei, fluorescence microscopy)";
    
    private final Map<String, StarDistModel> MODELS = new LinkedHashMap<String, StarDistModel>();
    
    @Parameter(label="", visibility=ItemVisibility.MESSAGE)
    private final String msgTitle = "<html>" + 
            "<table><tr valign='top'><td>" +
            "<h2>Object Detection with Star-convex Shapes</h2>" +
            "<a href='https://github.com/mpicbg-csbd/stardist'>https://github.com/mpicbg-csbd/stardist</a>" + 
            "<br/><br/><small>Please cite our paper if StarDist was helpful for your research. Thanks!</small>" + 
            "</td><td>&nbsp;&nbsp;<img src='"+getLogoUrl()+"' width='100' height='100'></img><td>" +
            "</tr></table>" +
            "</html>";

    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><b>Neural Network Prediction</b></html>")
    private final String predMsg = "<html><hr width='100'></html>";

    @Parameter(label="Input Image") //, autoFill=false)
    private Dataset input;
            
    @Parameter(label="Model",
               choices={MODEL_DSB2018_V1,
                        MODEL_FILE,
                        MODEL_URL}, style=ChoiceWidget.LIST_BOX_STYLE)
    private String modelChoice = MODEL_DSB2018_V1;
    
    @Parameter(label="Normalize Image")
    private boolean normalizeInput = true;
    
    @Parameter(label="Percentile low", stepSize="0.1", min="0", max="100", style=NumberWidget.SLIDER_STYLE, callback="percentileBottomChanged")
    private double percentileBottom = 1.0;
    
    @Parameter(label="Percentile high", stepSize="0.1", min="0", max="100", style=NumberWidget.SLIDER_STYLE, callback="percentileTopChanged")
    private double percentileTop = 99.8;

    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><br/><b>NMS Postprocessing</b></html>")
    private final String nmsMsg = "<html><br/><hr width='100'></html>";
    
    @Parameter(label="Label Image", type=ItemIO.OUTPUT)
    private Dataset label;

    @Parameter(label="Probability/Score Threshold", stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double probThresh = 0.5;

    @Parameter(label="Overlap Threshold", stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double nmsThresh = 0.4;

    @Parameter(label="Output Type", choices={"ROI Manager","Label Image","Both"}, style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String outputType = "Both";
    
    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><br/><b>Advanced Options</b></html>")
    private final String advMsg = "<html><br/><hr width='100'></html>";
    
    @Parameter(label=MODEL_FILE, required=false)
    private File modelFile;

    @Parameter(label=MODEL_URL, required=false)
    protected String modelUrl;    
    
    @Parameter(label="Number of Tiles", min="1", stepSize="1")
    private int nTiles = 1;

    @Parameter(label="Boundary Exclusion", min="0", stepSize="1")
    private int excludeBoundary = 2;

    @Parameter(label="Verbose")
    private boolean verbose = false;

    @Parameter(label="Restore Defaults", callback="restoreDefaults")
    private Button restoreDefaults;
    
    // ---------

    @Parameter
    private LogService log;

    @Parameter
    private UIService ui;

    @Parameter
    private CommandService command;

    @Parameter
    protected DatasetService dataset;
    
    
    public StarDist2D() {
        MODELS.put(MODEL_DSB2018_V1, new StarDistModel(StarDist2D.class.getClassLoader().getResource("models/2D/dsb2018_v1.zip"), 0.417819, 0.5));        
    }

    private void restoreDefaults() {
        modelChoice = MODEL_DSB2018_V1;
        normalizeInput = true;
        percentileBottom = 1.0;
        percentileTop = 99.8;
        probThresh = 0.5;
        nmsThresh = 0.4;
        outputType = "Both";
        nTiles = 1;
        excludeBoundary = 2;
        verbose = false;
    }
    
    private void percentileBottomChanged() {
        percentileTop = Math.max(percentileBottom, percentileTop);
    }

    private void percentileTopChanged() {
        percentileBottom = Math.min(percentileBottom, percentileTop);
    }

    private URL getLogoUrl() {
        return StarDist2D.class.getClassLoader().getResource("images/logo.png");
    }


    @Override
    public void run() {
        
        // TODO: check parameters
        // TODO: timelapse support
        
        File tmpModelFile = null;
        try {
            final HashMap<String, Object> paramsCNN = new HashMap<>();
            paramsCNN.put("input", input);
            paramsCNN.put("normalizeInput", normalizeInput);
            paramsCNN.put("percentileBottom", percentileBottom);
            paramsCNN.put("percentileTop", percentileTop);
            paramsCNN.put("clip", false);
            paramsCNN.put("nTiles", nTiles);
            paramsCNN.put("blockMultiple", 64);
            paramsCNN.put("overlap", 64);
            paramsCNN.put("batchSize", 1);
            paramsCNN.put("showProgressDialog", true);
            
            switch (modelChoice) {
            case MODEL_FILE:
                paramsCNN.put("modelFile", modelFile);
                break;
            case MODEL_URL:
                paramsCNN.put("modelUrl", modelUrl);
                break;
            default:
                StarDistModel pretrainedModel = MODELS.get(modelChoice);
                if (pretrainedModel.canGetFile()) {
                    File file = pretrainedModel.getFile();
                    paramsCNN.put("modelFile", file);
                    if (pretrainedModel.isTempFile())
                        tmpModelFile = file;
                } else {
                    paramsCNN.put("modelUrl", pretrainedModel.url);
                }
            }
            
            final Future<CommandModule> futureCNN = command.run(GenericNetwork.class, false, paramsCNN);
            final RandomAccessibleInterval<FloatType> prediction = (RandomAccessibleInterval<FloatType>) futureCNN.get().getOutput("output");
        
            final long[] shape = Intervals.dimensionsAsLongArray(prediction);
            final RandomAccessibleInterval<FloatType> probRAI = Views.hyperSlice(prediction, 2, 0);
            final RandomAccessibleInterval<FloatType> distRAI = Views.offsetInterval(prediction, new long[]{0,0,1}, new long[]{shape[0],shape[1],shape[2]-1});
            
            // is there a better way?
            // https://forum.image.sc/t/convert-randomaccessibleinterval-to-imgplus-or-dataset/8535/6
            final Dataset prob = dataset.create(new ImgPlus(dataset.create(probRAI), "prob", new AxisType[] { Axes.X, Axes.Y }));
            final Dataset dist = dataset.create(new ImgPlus(dataset.create(distRAI), "dist", new AxisType[] { Axes.X, Axes.Y, Axes.CHANNEL }));
            
            // ui.show(prob);
            // ui.show(dist);
                    
            final HashMap<String, Object> paramsNMS = new HashMap<>();
            paramsNMS.put("prob", prob);
            paramsNMS.put("dist", dist);
            paramsNMS.put("probThresh", probThresh);
            paramsNMS.put("nmsThresh", nmsThresh);
            paramsNMS.put("outputType", outputType);
            paramsNMS.put("excludeBoundary", excludeBoundary);
            paramsNMS.put("verbose", verbose);
            
            final Future<CommandModule> futureNMS = command.run(StarDistNMS2D.class, false, paramsNMS);
            label = (Dataset) futureNMS.get().getOutput("label");
        
        } catch (InterruptedException | ExecutionException | IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (tmpModelFile != null && tmpModelFile.exists())
                    tmpModelFile.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    
    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset input = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast.tif").getFile());
        ij.ui().show(input);

        final HashMap<String, Object> params = new HashMap<>();
        ij.command().run(StarDist2D.class, true, params);
    }

}
