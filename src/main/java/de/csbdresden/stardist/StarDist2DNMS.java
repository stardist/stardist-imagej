package de.csbdresden.stardist;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.stream.IntStream;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;

import ij.IJ;
import ij.ImagePlus;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, label = "StarDist 2D NMS", menu = {
        @Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC),
        @Menu(label = "StarDist"),
        @Menu(label = "Other"),
        @Menu(label = "StarDist 2D NMS (postprocessing only)", weight = 2)
}) 
public class StarDist2DNMS extends StarDist2DBase implements Command {
    
    @Parameter(label=Opt.PROB_IMAGE)
    private Dataset prob;

    @Parameter(label=Opt.DIST_IMAGE)
    private Dataset dist;

    @Parameter(label=Opt.LABEL_IMAGE, type=ItemIO.OUTPUT)
    private Dataset label;

    @Parameter(type=ItemIO.OUTPUT)
    private Candidates polygons;

    @Parameter(label=Opt.PROB_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);

    @Parameter(label=Opt.NMS_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);

    @Parameter(label=Opt.OUTPUT_TYPE, choices={Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH}, style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
    
    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE)
    private final String advMsg = "<html><u>Advanced</u></html>";

    @Parameter(label=Opt.EXCLUDE_BNDRY, min="0", stepSize="1")
    private int excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);

    @Parameter(label=Opt.VERBOSE)
    private boolean verbose = (boolean) Opt.getDefault(Opt.VERBOSE);

    @Parameter(label=Opt.RESTORE_DEFAULTS, callback="restoreDefaults")
    private Button restoreDefaults;
    
    // ---------

    private void restoreDefaults() {
        probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);
        nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);
        outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
        excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);
        verbose = (boolean) Opt.getDefault(Opt.VERBOSE);
    }

    // ---------

    @Override
    public void run() {
        if (!checkInputs()) return;

        final RandomAccessibleInterval<FloatType> probRAI = (RandomAccessibleInterval<FloatType>) prob.getImgPlus();
        final RandomAccessibleInterval<FloatType> distRAI = (RandomAccessibleInterval<FloatType>) dist.getImgPlus();
        
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);
        final boolean isTimelapse = probAxes.contains(Axes.TIME);

        if (isTimelapse) {
            final int probTimeDim = IntStream.range(0, probAxes.size()).filter(d -> prob.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final int distTimeDim = IntStream.range(0, distAxes.size()).filter(d -> dist.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final long numFrames = prob.getFrames();

            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI, probTimeDim, t), Views.hyperSlice(distRAI, distTimeDim, t), probThresh, excludeBoundary, verbose ? log : null);
                polygons.nms(nmsThresh);
                if (verbose)
                    log.info(String.format("frame %03d: %d polygon candidates, %d remain after non-maximum suppression", t, polygons.getSorted().size(), polygons.getWinner().size()));
                export(outputType, polygons, 1+t);
            }
        } else {
            final Candidates polygons = new Candidates(probRAI, distRAI, probThresh, excludeBoundary, verbose ? log : null);
            polygons.nms(nmsThresh);
            if (verbose)
                log.info(String.format("%d polygon candidates, %d remain after non-maximum suppression", polygons.getSorted().size(), polygons.getWinner().size()));
            export(outputType, polygons, 0);
        }
        
        label = labelImageToDataset(outputType);
        if (labelIsOutput(outputType))
            record("label");
        else
            record();
    }
    

    private boolean checkInputs() {
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);
        
        if (!( (prob.numDimensions() == 2 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y))) ||
               (prob.numDimensions() == 3 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.TIME))) ))
            return showError("Probability/Score must be a 2D image or timelapse.");

        if (!( (dist.numDimensions() == 3 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL))            && dist.getChannels() >= 3) ||
               (dist.numDimensions() == 4 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL, Axes.TIME)) && dist.getChannels() >= 3) ))
            return showError("Distance must be a 2D image or timelapse with at least three channels.");

        if ((prob.numDimensions() + 1) != dist.numDimensions())
            return showError("Axes of Probability/Score and Distance not compatible.");

        if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
            return showError("Width or height of Probability/Score and Distance differ.");

        if (prob.getFrames() != dist.getFrames())
            return showError("Number of frames of Probability/Score and Distance differ.");
        
        final AxisType[] probAxesArray = probAxes.stream().toArray(AxisType[]::new);
        final AxisType[] distAxesArray = distAxes.stream().toArray(AxisType[]::new);
        if (!( probAxesArray[0] == Axes.X && probAxesArray[1] == Axes.Y ))
            return showError("First two axes of Probability/Score must be a X and Y.");
        if (!( distAxesArray[0] == Axes.X && distAxesArray[1] == Axes.Y ))
            return showError("First two axes of Distance must be a X and Y.");        

        if (!(0 <= nmsThresh && nmsThresh <= 1))
            return showError("NMS Threshold must be between 0 and 1.");

        if (excludeBoundary < 0)
            return showError("Boundary Exclusion must be >= 0");

        if (!(outputType.equals(Opt.OUTPUT_ROI_MANAGER) || outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH) || outputType.equals(Opt.OUTPUT_POLYGONS)))
            return showError(String.format("Output Type must be one of {\"%s\", \"%s\", \"%s\"}.", Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH));
        
        if (outputType.equals(Opt.OUTPUT_POLYGONS) && probAxes.contains(Axes.TIME))
            return showError(String.format("Timelapse not supported for output type \"%s\"", Opt.OUTPUT_POLYGONS));        
        
        return true;
    }

    
    @Override
    protected void exportPolygons(Candidates polygons) {
        this.polygons = polygons;
    }
    
    @Override
    protected ImagePlus createLabelImage() {
        return IJ.createImage("Labeling", "16-bit black", (int)prob.getWidth(), (int)prob.getHeight(), 1, 1, (int)prob.getFrames());
    }

    
    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset prob = ij.scifio().datasetIO().open(StarDist2DNMS.class.getClassLoader().getResource("blobs_prob.tif").getFile());
        Dataset dist = ij.scifio().datasetIO().open(StarDist2DNMS.class.getClassLoader().getResource("blobs_dist.tif").getFile());

        ij.ui().show(prob);
        ij.ui().show(dist);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("prob", prob);
        params.put("dist", dist);
        ij.command().run(StarDist2DNMS.class, true, params);

        IJ.run("Tile");
    }

}
