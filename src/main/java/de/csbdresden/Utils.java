package de.csbdresden;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import de.lighti.clipper.Path;
import de.lighti.clipper.Point.LongPoint;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import net.imagej.Dataset;
import net.imagej.axis.AxisType;

public class Utils {

    public static PolygonRoi toPolygonRoi(Path poly) {
        int n = poly.size();
        int[] x = new int[n];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            LongPoint p = poly.get(i);
            x[i] = (int) p.getX();
            y[i] = (int) p.getY();
        }
        return new PolygonRoi(x,y,n,Roi.POLYGON);
    }

    public static double[] rayAngles(int n) {
        double[] angles = new double[n];
        double st = (2*Math.PI)/n;
        for (int i = 0; i < n; i++) angles[i] = st*i;
        return angles;
    }

    public static List<Integer> argsortDescending(final List<Float> list) {
        Integer[] indices = new Integer[list.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, new Comparator<Integer>() {
            @Override
            public int compare(Integer i, Integer j) {
                return -Float.compare(list.get(i), list.get(j));
            }
        });
        return Arrays.asList(indices);
    }

    public static LinkedHashSet<AxisType> orderedAxesSet(Dataset image) {
        final int numDims = image.numDimensions();
        final LinkedHashSet<AxisType> axes = new LinkedHashSet<>(numDims);
        for (int d = 0; d < numDims; d++)
            axes.add(image.axis(d).type());
        return axes;
    }

}
