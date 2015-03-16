package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import edu.tamu.tcat.analytics.image.integral.IntegralImage;
import edu.tamu.tcat.analytics.image.integral.IntegralImageImpl;
import edu.tamu.tcat.analytics.image.region.BoundingBox;
import edu.tamu.tcat.analytics.image.region.Point;
import edu.tamu.tcat.dia.binarization.BinarizationException;
import edu.tamu.tcat.dia.binarization.BinaryImage;
import edu.tamu.tcat.dia.binarization.sauvola.FastSauvola;
import edu.tamu.tcat.dia.segmentation.cc.ConnectComponentSet;
import edu.tamu.tcat.dia.segmentation.cc.ConnectedComponent;
import edu.tamu.tcat.dia.segmentation.cc.twopass.ConnectedComponentFinder;

/**
 * 
 * Implements Fletcher, Lloyd Alan; Kasturi, Rangachar (1988). A Robust Algorithm for 
 * Text String Separation from Mixed Text/Graphics Images 
 */
public class Fletcher
{
   private static final double halfPi = Math.PI / 2;
   private static FastSauvola binarizer = new FastSauvola();

   public Fletcher()
   {
      // TODO Auto-generated constructor stub
   }

   public List<ConnectedComponent> process(Collection<ConnectedComponent> ccSet) throws BinarizationException
   {
      List<ConnectedComponent> textCandidateCCs = filter(ccSet);
      
      return textCandidateCCs;
   }
   
   private List<ConnectedComponent> filter(Collection<ConnectedComponent> ccSet)
   {
      // create area histogram
      int ratioThreshold = 20;
      double inverseRatioThresh = 1.0/ratioThreshold;
      
      int[] areas = ccSet.stream().mapToInt(cc -> cc.getBounds().getArea()).toArray();
      double areaRatio = computeAreaThreshold(areas, 25);
      
      List<ConnectedComponent> candidates = ccSet.stream()
            .filter((cc) -> cc.getBounds().getArea() < areaRatio)
            .filter((cc) -> {
               BoundingBox b = cc.getBounds();
               double ratio = (double)b.getWidth() / b.getHeight();
               return !(ratio > ratioThreshold || ratio < inverseRatioThresh);
            })
            .collect(Collectors.toList());
      
      // TODO 
      // 1.  calcuate average height of components Hws
      double avgHeight = candidates.stream().mapToInt(cc -> cc.getBounds().getHeight()).sum() / (double)candidates.size();
      double radialResolution = 0.2 * avgHeight;
      
      
      // define angles range 0 <= theta <= 5 and 85 <= 0 <= 95,
      double[] angleValues = computeMainAngles(); 
      HoughTransform<ConnectedComponent> accumulator = 
            new HoughTransform<>(cc -> cc.getCentroid(), angleValues, radialResolution);
      
      candidates.stream().forEach(accumulator::addObservation);
      
      performStuff(accumulator, 20);      // loop 20 to 1
      
      // 2.  set the Hough domain resolution R to 0.2 X Hws, set a counter to zero
      // 3.  Apply Hough transform to all components in the working set for theta in the 
      //     range 0 <= theta <= 5 and 85 <= 0 <= 95, set the running threshold RT = 20
      // 4.  For each cell having a count greater than RT
      //     4a. Form a cluster of the 11 rho cells (constant theta) including the primary cell
      //         clustered around primary cell
      //     4b. Compute the average height of components in the cluser (Ha)
      //     4c. Compute the new clustering factor cf = Ha / R
      //     4d. Re cluster with plus/minus cf cells centered around primary
      //     4e. perform string segmentation
      //     4f. delete contributions from discarded step
      //     4g. update Hough transform
      //     4h. decrement RT by one (repate while RT > 2
      // 5. Other stuff.
      
      
      
      
      return candidates;
   }

   /**
    * For each cell having a count greater than thresh in the transform, DO STUFF
    * @param transform
    * @param thres
    */
   private void performStuff(HoughTransform<ConnectedComponent> transform, int thres)
   {
      Collection<HoughAccumulator<ConnectedComponent>> accumulators = transform.getAccumulators();
      
      List<HoughAccumulator<ConnectedComponent>> filtered = accumulators.stream()
                     .filter(acc -> acc.size() > thres)
                     .collect(Collectors.toList());
      for (HoughAccumulator<ConnectedComponent> primayCell : filtered)
      {
         //     4a. Form a cluster of the 11 rho cells (constant theta) including the primary cell
         //         clustered around primary cell
         //     4b. Compute the average height of components in the cluser (Ha)
         transform.getByAngle(primayCell);
         
      }
      // For each cell having a count greater than RT
      //     4a. Form a cluster of the 11 rho cells (constant theta) including the primary cell
      //         clustered around primary cell
      //     4b. Compute the average height of components in the cluser (Ha)
      //     4c. Compute the new clustering factor cf = Ha / R
      //     4d. Re cluster with plus/minus cf cells centered around primary
      //     4e. perform string segmentation
      //     4f. delete contributions from discarded step
      //     4g. update Hough transform
      //     4h. decrement RT by one (repate while RT > 2
      // TODO Auto-generated method stub
      
   }

   private double[] computeMainAngles()
   {
      List<Double> angles = new ArrayList<>();
      double aRes = Math.PI / 180;     // HACK hard code one degree angular resolution
      double range = Math.PI / 36;
      for (double theta = 0; theta < range; theta += aRes)
      {
         angles.add(Double.valueOf(theta));
      }
      
      for (double theta = (halfPi - range); theta < (halfPi + range); theta += aRes)
      {
         angles.add(Double.valueOf(theta));
      }
      
      double[] angleValues = angles.parallelStream().mapToDouble(Double::doubleValue).toArray();
      return angleValues;
   }
   
   /**
    * 
    * @param p The point to convert to Hough space.
    * @return A function representing the line in the Hough domain for the supplied point.
    *    For some value Θ returns {@code rho = x cos Θ + y sin Θ} 
    */
   private static DoubleUnaryOperator toHoughLine(Point p) 
   {
      final int x = p.getX();
      final int y = p.getY();
      
      return (theta) -> x * Math.cos(theta) + y * Math.sin(theta);
   }
   private static double computeAreaThreshold(int[] areas, int nbins)
   {
      DoubleSummaryStatistics stats = IntStream.of(areas)
            .collect(DoubleSummaryStatistics::new,
                  DoubleSummaryStatistics::accept,
                  DoubleSummaryStatistics::combine);
      
      double max = stats.getMax();
      double min = stats.getMin();
      final double binSize = (max - min) / nbins;
      int[] histogram = IntStream.of(areas).collect(
            () -> new int[nbins], 
            (histogramMemo, a) -> { 
               int ix = (int)Math.floor((a - min) / binSize);
               if (ix == nbins)
                  ix--;
               histogramMemo[ix] = histogramMemo[ix] + 1;
            }, 
            (a, b) -> IntStream.range(0, nbins).parallel().map(i -> a[i] + b[i]).toArray()
      );
      
      int maxCount = IntStream.of(histogram).max().orElse(0);
      int[] matched = IntStream.range(0, histogram.length).filter(i -> histogram[i] >= maxCount).toArray();
      if (matched.length == 0)
         throw new IllegalArgumentException();
      
      
      double areaThreshold = binSize * matched[0] + min;
      return Math.max(areaThreshold, stats.getAverage()) * 5;
   }
   
   private Set<ConnectedComponent> findConnectedComponents(BufferedImage image) throws BinarizationException
   {
      IntegralImage integralImage = IntegralImageImpl.create(image);
      BinaryImage binaryImage = binarizer.binarize(integralImage);
      ConnectedComponentFinder finder = new ConnectedComponentFinder(binaryImage, 100_000);
      ConnectComponentSet components = finder.call();

      Set<ConnectedComponent> ccSet = components.asSet(); //.stream()
//            .filter(cc -> cc.getBounds().getArea() > minComponentSize)   
//            .collect(Collectors.toSet());

      return ccSet;
   }
}
