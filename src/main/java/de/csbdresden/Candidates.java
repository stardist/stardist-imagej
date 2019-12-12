package de.csbdresden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import de.lighti.clipper.Clipper;
import de.lighti.clipper.DefaultClipper;
import de.lighti.clipper.Path;
import de.lighti.clipper.Paths;
import de.lighti.clipper.Point.LongPoint;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;

public class Candidates {
    
    private final List<Path> polygons = new ArrayList<>();
    private final List<Point2D> origins = new ArrayList<>();
    private final List<Box2D> bboxes = new ArrayList<>();
    private final List<Double> areas = new ArrayList<>();
    private final List<Float> scores = new ArrayList<>();
    private final List<Integer> score_indices;
    private final List<Integer> winner = new ArrayList<>();
    private final boolean[] suppressed;
    private final boolean verbose;
    
    public Candidates(RandomAccessibleInterval<FloatType> prob, RandomAccessibleInterval<FloatType> dist) {
        this(prob, dist, 0.4);
    }

    public Candidates(RandomAccessibleInterval<FloatType> prob, RandomAccessibleInterval<FloatType> dist, double threshold) {
        this(prob, dist, threshold, 2, false);
    }

    public Candidates(RandomAccessibleInterval<FloatType> prob, RandomAccessibleInterval<FloatType> dist, double threshold, int b, boolean verbose) {        
        final long start = System.currentTimeMillis();
        this.verbose = verbose;
        
        final long[] shape = Intervals.dimensionsAsLongArray(dist);
        final int ndim = shape.length;
        assert ndim == 3;

        int nrays = (int)shape[2];
        final double[] phis = Utils.rayAngles(nrays);
        
        final RandomAccess<FloatType> r = prob.randomAccess();
        final RandomAccess<FloatType> s = dist.randomAccess();
        
        for (int i = b; i < shape[0]-b; i++) {
            for (int j = b; j < shape[1]-b; j++) {
                r.setPosition(i, 0); r.setPosition(j, 1);
                s.setPosition(i, 0); s.setPosition(j, 1);
                final float score = r.get().getRealFloat();
                if (score > threshold) {
                    final Path poly = new Path();
                    long xmin = Long.MAX_VALUE, xmax = Long.MIN_VALUE;
                    long ymin = Long.MAX_VALUE, ymax = Long.MIN_VALUE;
                    for (int k = 0; k < nrays; k++) {
                        s.setPosition(k, 2);
                        FloatType d = s.get();
                        long x = (long) Math.round(i + d.getRealDouble() * Math.cos(phis[k]));
                        long y = (long) Math.round(j + d.getRealDouble() * Math.sin(phis[k]));
                        xmin = Math.min(xmin,x);
                        ymin = Math.min(ymin,y);
                        xmax = Math.max(xmax,x);
                        ymax = Math.max(ymax,y);
                        poly.add(new LongPoint(x,y));                        
                    }
                    polygons.add(poly);
                    bboxes.add(new Box2D(xmin,xmax,ymin,ymax));
                    origins.add(new Point2D(i,j));
                    scores.add(score);
                    areas.add(poly.area());
                }
            }
        }
        score_indices = Utils.argsortDescending(scores);
        suppressed = new boolean[polygons.size()];
        
        if (verbose)
            System.out.printf("Candidates constructor took %d ms\n", System.currentTimeMillis() - start);
    }
    
    public void nms_v0(final double threshold) {
        final long start = System.currentTimeMillis();
        // TODO: apply same trick (bbox search window) as in c++ version
        Arrays.fill(suppressed, false);
        winner.clear();
        final int n = polygons.size();
        for (int ii = 0; ii < n; ii++) {
            final int i = score_indices.get(ii);
            if (suppressed[i]) continue;
            winner.add(i);
            final Box2D bbox = bboxes.get(i);
            for (int jj = ii+1; jj < n; jj++) {
                final int j = score_indices.get(jj);
                if (suppressed[j]) continue;
                if (bbox.does_intersect(bboxes.get(j))) {
                    final double area_inter = poly_intersection_area(polygons.get(i), polygons.get(j));
                    final double overlap = area_inter / Math.min(areas.get(i)+1e-10, areas.get(j)+1e-10);
                    if (overlap > threshold)
                        suppressed[j] = true;
                }
            }
        }
        if (verbose)
            System.out.printf("Candidates NMS took %d ms\n", System.currentTimeMillis() - start);
    }

    public void nms(final double threshold) {
        final long start = System.currentTimeMillis();
        // TODO: apply same trick (bbox search window) as in c++ version
        Arrays.fill(suppressed, false);
        winner.clear();
        final int n = polygons.size();
        for (int ii = 0; ii < n; ii++) {
            final int i = score_indices.get(ii);
            if (suppressed[i]) continue;
            winner.add(i);
            final Box2D bbox = bboxes.get(i);
            // 
            IntStream.range(ii+1, n)
            .parallel()
            // .peek(val -> System.out.println(Thread.currentThread().getName()))
            .forEach(jj -> {
                final int j = score_indices.get(jj);
                if (suppressed[j]) return;
                if (bbox.does_intersect(bboxes.get(j))) {
                    final double area_inter = poly_intersection_area(polygons.get(i), polygons.get(j));
                    final double overlap = area_inter / Math.min(areas.get(i)+1e-10, areas.get(j)+1e-10);
                    if (overlap > threshold)
                        suppressed[j] = true;
                }
            });
        }
        if (verbose)
            System.out.printf("Candidates NMS took %d ms\n", System.currentTimeMillis() - start);
    }

    private double poly_intersection_area(final Path a, final Path b) {
        final Clipper c = new DefaultClipper();
        final Paths res = new Paths();
        c.clear();
        c.addPath(a, Clipper.PolyType.CLIP, true);
        c.addPath(b, Clipper.PolyType.SUBJECT, true);
        c.execute(Clipper.ClipType.INTERSECTION, res, Clipper.PolyFillType.NON_ZERO, Clipper.PolyFillType.NON_ZERO);
        double area_inter = 0;
        for (Path p : res)
            area_inter += p.area();
        return area_inter;
    }
    
    public List<Integer> getWinner() {
        return winner;
    }
    
    public List<Integer> getSorted() {
        return score_indices;
    }
    
    public Point2D getOrigin(int i) {
        return origins.get(i);
    }
    
    public Path getPolygon(int i) {
        return polygons.get(i);
    }

    public Box2D getBbox(int i) {
        return bboxes.get(i);
    }

    public float getScore(int i) {
        return scores.get(i);
    }

    public double getArea(int i) {
        return areas.get(i);
    }


}
