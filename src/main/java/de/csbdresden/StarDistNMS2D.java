package de.csbdresden;

import java.util.HashMap;

import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import ij.IJ;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > 2D Non-maximum Suppression", label = "StarDist 2D NMS")
public class StarDistNMS2D<T extends RealType<T>> implements Command {

    @Parameter(label="Probability/Score Image")
    private Dataset probDs;

    @Parameter(label="Distance Image")
    private Dataset distDs;
    
    @Parameter(label="Probability/Score Threshold", stepSize="0.1")
    private double probThresh = 0.5;

    @Parameter(label="NMS Overlap Threshold", stepSize="0.1", min="0", max="1")
    private double nmsThresh = 0.4;

    @Parameter(label="Boundary Exclusion", stepSize="1", min="0")
    private int excludeBoundary = 2;

    @Parameter(label="Verbose", description = "Verbose output")
    private boolean verbose = false;

    @Parameter(label="Output Kind", choices={"ROI Manager","Label Image","Both"})
    private String outputKind = "ROI Manager";

    @Parameter
    private UIService uiService;

    @Parameter
    private OpService opService;

    @Parameter
    private CommandService commandService;

    
    private boolean exportPointRois = false;
    
    private boolean exportBboxRois = false;
    
    private RoiManager roiManager = null;
    
    
    @Override
    public void run() {
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> prob = (RandomAccessibleInterval<FloatType>) probDs.getImgPlus();
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> dist = (RandomAccessibleInterval<FloatType>) distDs.getImgPlus();
        
        if (!( (probDs.numDimensions() == 2 && probDs.axis(0).type() == Axes.X && probDs.axis(1).type() == Axes.Y) ||
               (probDs.numDimensions() == 3 && probDs.axis(0).type() == Axes.X && probDs.axis(1).type() == Axes.Y && probDs.axis(2).type() == Axes.TIME) ))
            throw new IllegalArgumentException("Probability/Score must be a 2D image or timelapse");

        if (!( (distDs.numDimensions() == 3 && distDs.axis(0).type() == Axes.X && distDs.axis(1).type() == Axes.Y && distDs.axis(2).type() == Axes.CHANNEL && distDs.getChannels() >= 3) ||
               (distDs.numDimensions() == 4 && distDs.axis(0).type() == Axes.X && distDs.axis(1).type() == Axes.Y && distDs.axis(2).type() == Axes.CHANNEL && distDs.getChannels() >= 3 && distDs.axis(3).type() == Axes.TIME) ))
            throw new IllegalArgumentException("Distance must be a 2D image or timelapse with at least three channels");
        
        if ((probDs.numDimensions() + 1) != distDs.numDimensions())
            throw new IllegalArgumentException("Axes of Probability/Score and Distance not compatible");
        
        final boolean isTimelapse = probDs.numDimensions() == 3;
        
        if (isTimelapse && probDs.getFrames() != distDs.getFrames())
            throw new IllegalArgumentException("Number of frames of Probability/Score and Distance differ");
        
        if (verbose) {
            System.out.printf("probThresh = %f\n", probThresh);
            System.out.printf("nmsThresh = %f\n", nmsThresh);
            System.out.printf("excludeBoundary = %d\n", excludeBoundary);
            System.out.printf("verbose = %s\n", verbose);
        }
        
        if (isTimelapse) {
            final long numFrames = probDs.getFrames();
            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(prob,2,t), Views.hyperSlice(dist,3,t), probThresh, excludeBoundary, verbose);
                polygons.nms(nmsThresh);
                if (verbose) {
                    System.out.printf("frame %04d: %d polygon candidates before non-maximum suppression\n", t, polygons.getSorted().size());
                    System.out.printf("frame %04d: %d polygons remain after non-maximum suppression\n", t, polygons.getWinner().size());
                }
                export(polygons, 1+t);
            }
        } else {
            final Candidates polygons = new Candidates(prob, dist, probThresh, excludeBoundary, verbose);
            polygons.nms(nmsThresh);
            if (verbose) {
                System.out.printf("%d polygon candidates before non-maximum suppression\n", polygons.getSorted().size());
                System.out.printf("%d polygons remain after non-maximum suppression\n", polygons.getWinner().size());
            }
            export(polygons, 0);
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
        System.out.println("Label image output not implemented");
    }
    

    public static void main(final String... args) throws Exception {
        
        final ImageJ ij = new ImageJ();
        ij.launch(args);
        // ij.ui().showUI();
        
        Dataset probDs = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_prob.tif").getFile());
        Dataset distDs = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_dist.tif").getFile());
        
        

        ij.ui().show(probDs);
        ij.ui().show(distDs);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("probDs", probDs);
        params.put("distDs", distDs);
        ij.command().run(StarDistNMS2D.class, true, params);

        IJ.run("Tile");                
    }

}
