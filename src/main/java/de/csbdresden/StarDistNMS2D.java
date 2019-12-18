package de.csbdresden;

import java.util.HashMap;

import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.IJ;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > 2D Non-maximum Suppression", label = "StarDist 2D NMS")
public class StarDistNMS2D<T extends RealType<T>> implements Command {

    @Parameter(label="Probability/Score Image")
    private Dataset prob;

    @Parameter(label="Distance Image")
    private Dataset dist;
    
    @Parameter(label="Probability/Score Threshold", stepSize="0.1")
    private double probThresh = 0.5;

    @Parameter(label="NMS Overlap Threshold", stepSize="0.1", min="0", max="1")
    private double nmsThresh = 0.4;

    @Parameter(label="Boundary Exclusion", stepSize="1", min="0")
    private int excludeBoundary = 2;

    @Parameter(label="Verbose", description="Verbose output")
    private boolean verbose = false;

//    @Parameter(label="Output Kind", choices={"ROI Manager","Label Image","Both"})
    private String outputKind = "ROI Manager";

//    @Parameter
//    private UIService uiService;
//
//    @Parameter
//    private OpService opService;
//
//    @Parameter
//    private CommandService commandService;

    @Parameter
    private LogService log;
    
    
    private boolean exportPointRois = false;
    
    private boolean exportBboxRois = false;
    
    private RoiManager roiManager = null;
    
    
    @Override
    public void run() {
        checkInputs();
        
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> probRAI = (RandomAccessibleInterval<FloatType>) prob.getImgPlus();
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> distRAI = (RandomAccessibleInterval<FloatType>) dist.getImgPlus();
                
        final long numFrames = prob.getFrames();
        final boolean isTimelapse = numFrames > 1;
        
        if (isTimelapse) {
            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI,2,t), Views.hyperSlice(distRAI,3,t), probThresh, excludeBoundary, verbose ? log : null);
                polygons.nms(nmsThresh);
                if (verbose)
                    log.info(String.format("frame %03d: %d polygon candidates, %d remain after non-maximum suppression\n", t, polygons.getSorted().size(), polygons.getWinner().size()));
                export(polygons, 1+t);
            }
        } else {
            final Candidates polygons = new Candidates(probRAI, distRAI, probThresh, excludeBoundary, verbose ? log : null);
            polygons.nms(nmsThresh);
            if (verbose)
                log.info(String.format("%d polygon candidates, %d remain after non-maximum suppression\n", polygons.getSorted().size(), polygons.getWinner().size()));
            export(polygons, 0);
        }
    }
    
    private void checkInputs() {
         if (!( (prob.numDimensions() == 2 && prob.axis(0).type() == Axes.X && prob.axis(1).type() == Axes.Y) ||
                (prob.numDimensions() == 3 && prob.axis(0).type() == Axes.X && prob.axis(1).type() == Axes.Y && prob.axis(2).type() == Axes.TIME) ))
             throw new IllegalArgumentException("Probability/Score must be a 2D image or timelapse");

         if (!( (dist.numDimensions() == 3 && dist.axis(0).type() == Axes.X && dist.axis(1).type() == Axes.Y && dist.axis(2).type() == Axes.CHANNEL && dist.getChannels() >= 3) ||
                (dist.numDimensions() == 4 && dist.axis(0).type() == Axes.X && dist.axis(1).type() == Axes.Y && dist.axis(2).type() == Axes.CHANNEL && dist.getChannels() >= 3 && dist.axis(3).type() == Axes.TIME) ))
             throw new IllegalArgumentException("Distance must be a 2D image or timelapse with at least three channels");
         
         if ((prob.numDimensions() + 1) != dist.numDimensions())
             throw new IllegalArgumentException("Axes of Probability/Score and Distance not compatible");
         
         if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
             throw new IllegalArgumentException("Width or height of Probability/Score and Distance differ");
         
         if (prob.getFrames() != dist.getFrames())
             throw new IllegalArgumentException("Number of frames of Probability/Score and Distance differ");

         if (!(0 <= nmsThresh && nmsThresh < 1))
             throw new IllegalArgumentException("NMS Threshold must be in interval [0,1).");
         
         // TODO: if (!(excludeBoundary >= 0 && 2*excludeBoundary < min size of any x,y dimension)) ...

         // TODO: outputKind must be one of {"ROI Manager","Label Image","Both"}
         
         if (verbose) {
             log.info(String.format("probThresh = %f\n", probThresh));
             log.info(String.format("nmsThresh = %f\n", nmsThresh));
             log.info(String.format("excludeBoundary = %d\n", excludeBoundary));
             log.info(String.format("verbose = %s\n", verbose));
             log.info(String.format("outputKind = %s\n", outputKind));
         }
    }
    
    
    private void export(Candidates polygons, int framePosition) {
        switch (outputKind) {
        case "ROI Manager":
            exportROIs(polygons, framePosition);
            break;
        case "Label Image":
            exportLabelImage(polygons, framePosition);
            break;
        case "Both":
            exportROIs(polygons, framePosition);
            exportLabelImage(polygons, framePosition);
            break;
        }
    }
    
    
    private void exportROIs(Candidates polygons, int framePosition) {
        if (roiManager == null) {
            IJ.run("ROI Manager...", "");
            roiManager = RoiManager.getInstance();
        }
        for (final int i : polygons.getWinner()) {
            final PolygonRoi polyRoi = Utils.toPolygonRoi(polygons.getPolygon(i));
            // if (framePosition > 0) polyRoi.setPosition(0, 0, framePosition);
            if (framePosition > 0) polyRoi.setPosition(framePosition);
            roiManager.addRoi(polyRoi);
            if (exportPointRois) {
                final Point2D o = polygons.getOrigin(i);
                final PointRoi pointRoi = new PointRoi(o.x, o.y);
                // if (framePosition > 0) pointRoi.setPosition(0, 0, framePosition);
                if (framePosition > 0) pointRoi.setPosition(framePosition);
                roiManager.addRoi(pointRoi);
            }
            if (exportBboxRois) {
                final Box2D bbox = polygons.getBbox(i);
                final Roi bboxRoi = new Roi(bbox.xmin, bbox.ymin, bbox.xmax - bbox.xmin, bbox.ymax - bbox.ymin);
                // if (framePosition > 0) bboxRoi.setPosition(0, 0, framePosition);
                if (framePosition > 0) bboxRoi.setPosition(framePosition);
                roiManager.addRoi(bboxRoi);
            }
        }            
    }
    
    
    private void exportLabelImage(Candidates polygons, int framePosition) {
        log.warn("Label image output not implemented");
    }
    

    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);
        
        Dataset prob = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_prob.tif").getFile());
        Dataset dist = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_dist.tif").getFile());
        
        ij.ui().show(prob);
        ij.ui().show(dist);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("prob", prob);
        params.put("dist", dist);
        ij.command().run(StarDistNMS2D.class, true, params);

        IJ.run("Tile");                
    }

}
