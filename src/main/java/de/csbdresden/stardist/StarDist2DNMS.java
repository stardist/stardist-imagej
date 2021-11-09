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

import de.csbdresden.CommandFromMacro;
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
    
    @Parameter(label=Opt.ROI_POSITION, choices={Opt.ROI_POSITION_STACK, Opt.ROI_POSITION_HYPERSTACK}, style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String roiPosition = (String) Opt.getDefault(Opt.ROI_POSITION);

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
        roiPosition = (String) Opt.ROI_POSITION_STACK;
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
        final AxisType[] axesOut = isTimelapse ? new AxisType[]{Axes.X, Axes.Y, Axes.TIME} : new AxisType[]{Axes.X, Axes.Y};

        if (isTimelapse) {
            final int probTimeDim = IntStream.range(0, probAxes.size()).filter(d -> prob.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final int distTimeDim = IntStream.range(0, distAxes.size()).filter(d -> dist.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final long numFrames = prob.getFrames();

            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI, probTimeDim, t), Views.hyperSlice(distRAI, distTimeDim, t), probThresh, excludeBoundary, verbose ? log : null);
                polygons.nms(nmsThresh);
                if (verbose)
                    log.info(String.format("frame %03d: %d polygon candidates, %d remain after non-maximum suppression", t, polygons.getSorted().size(), polygons.getWinner().size()));
                export(outputType, polygons, 1+t, numFrames, roiPosition);
            }
        } else {
            final Candidates polygons = new Candidates(probRAI, distRAI, probThresh, excludeBoundary, verbose ? log : null);
            polygons.nms(nmsThresh);
            if (verbose)
                log.info(String.format("%d polygon candidates, %d remain after non-maximum suppression", polygons.getSorted().size(), polygons.getWinner().size()));
            export(outputType, polygons, 0, 0, roiPosition);
        }

        label = labelImageToDataset(outputType);

        // copy axis calibration from prob
        Utils.copyCalibration(prob, label, axesOut);

        // call at the end of the run() method
        CommandFromMacro.record(this, this.command);
    }


    private boolean checkInputs() {
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);

        if (!( (prob.numDimensions() == 2 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y))) ||
               (prob.numDimensions() == 3 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.TIME))) ))
            return showError(String.format("%s must be a 2D image or timelapse.", Opt.PROB_IMAGE));

        if (!( (dist.numDimensions() == 3 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL))            && dist.getChannels() >= 3) ||
               (dist.numDimensions() == 4 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL, Axes.TIME)) && dist.getChannels() >= 3) ))
            return showError(String.format("%s must be a 2D image or timelapse with at least three channels.", Opt.DIST_IMAGE));

        if ((prob.numDimensions() + 1) != dist.numDimensions())
            return showError(String.format("Axes of %s and %s not compatible.", Opt.PROB_IMAGE, Opt.DIST_IMAGE));

        if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
            return showError(String.format("Width or height of %s and %s differ.", Opt.PROB_IMAGE, Opt.DIST_IMAGE));

        if (prob.getFrames() != dist.getFrames())
            return showError(String.format("Number of frames of %s and %s differ.", Opt.PROB_IMAGE, Opt.DIST_IMAGE));

        final AxisType[] probAxesArray = probAxes.stream().toArray(AxisType[]::new);
        final AxisType[] distAxesArray = distAxes.stream().toArray(AxisType[]::new);
        if (!( probAxesArray[0] == Axes.X && probAxesArray[1] == Axes.Y ))
            return showError(String.format("First two axes of %s must be a X and Y.", Opt.PROB_IMAGE));
        if (!( distAxesArray[0] == Axes.X && distAxesArray[1] == Axes.Y ))
            return showError(String.format("First two axes of %s must be a X and Y.", Opt.DIST_IMAGE));

        if (!(0 <= nmsThresh && nmsThresh <= 1))
            return showError(String.format("%s must be between 0 and 1.", Opt.NMS_THRESH));

        if (excludeBoundary < 0)
            return showError(String.format("%s must be >= 0", Opt.EXCLUDE_BNDRY));

        if (!(outputType.equals(Opt.OUTPUT_ROI_MANAGER) || outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH) || outputType.equals(Opt.OUTPUT_POLYGONS)))
            return showError(String.format("%s must be one of {\"%s\", \"%s\", \"%s\"}.", Opt.OUTPUT_TYPE, Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH));

        if (outputType.equals(Opt.OUTPUT_POLYGONS) && probAxes.contains(Axes.TIME))
            return showError(String.format("Timelapse not supported for output type \"%s\"", Opt.OUTPUT_POLYGONS));

        if (!(roiPosition.equals(Opt.ROI_POSITION_STACK) || roiPosition.equals(Opt.ROI_POSITION_HYPERSTACK)))
            return showError(String.format("%s must be one of {\"%s\", \"%s\"}.", Opt.ROI_POSITION, Opt.ROI_POSITION_STACK, Opt.ROI_POSITION_HYPERSTACK));        
        
        return true;
    }


    @Override
    protected void exportPolygons(Candidates polygons) {
        this.polygons = polygons;
    }

    @Override
    protected ImagePlus createLabelImage() {
        return IJ.createImage(Opt.LABEL_IMAGE, "16-bit black", (int)prob.getWidth(), (int)prob.getHeight(), 1, 1, (int)prob.getFrames());
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
