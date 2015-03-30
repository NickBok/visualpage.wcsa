package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
import edu.tamu.tcat.dia.segmentation.cc.twopass.CCWriter;
import edu.tamu.tcat.dia.segmentation.cc.twopass.ConnectedComponentFinder;
import edu.tamu.tcat.visualpage.wcsa.fletcher.HoughTransform.AngleColumn;
import edu.tamu.tcat.visualpage.wcsa.importer.ImageProxy;

/**
 * 
 * Implements Fletcher, Lloyd Alan; Kasturi, Rangachar (1988). A Robust Algorithm for 
 * Text String Separation from Mixed Text/Graphics Images 
 */
public class FletcherGraphicsSegmentation
{
   private static final double halfPi = Math.PI / 2;
   private static FastSauvola binarizer = new FastSauvola();
   private ImageProxy proxy;
   private Collection<ConnectedComponent> ccSet;

   public FletcherGraphicsSegmentation(ImageProxy proxy, Collection<ConnectedComponent> ccSet)
   {
      this.proxy = proxy;
      this.ccSet = ccSet;
   }

   public Set<ConnectedComponent> process() throws BinarizationException
   {
      List<ConnectedComponent> candidates = performAreaThresholding(ccSet, 20);
      
      // TODO this still isn't right. We should perform in two or three passes, first extracting 
      //      horizontal 'strings', then vertical strings, then all remaining items.
      // TODO need way to visualize intermediate results (strings)
      double avgHeight = candidates.stream().mapToInt(cc -> cc.getBounds().getHeight()).sum() / (double)candidates.size();
      double radialResolution = 0.2 * avgHeight;
      
      // define angles range 0 <= theta <= 5 and 85 <= 0 <= 95,
      Set<ConnectedComponent> textCandidateCCs = performTextIdentification(candidates, generateHorizontalAngles(), radialResolution);
      candidates.removeAll(textCandidateCCs);
      textCandidateCCs.addAll(performTextIdentification(candidates, generateVerticalAngles(), radialResolution));
      candidates.removeAll(textCandidateCCs);
      textCandidateCCs.addAll(performTextIdentification(candidates, generateAllAngles(), radialResolution));
      
      return textCandidateCCs;
   }

   private Set<ConnectedComponent> performTextIdentification(List<ConnectedComponent> candidates, double[] angleValues, double radialResolution)
   {
      HoughTransform<ConnectedComponent> transform = 
            new HoughTransform<>(cc -> cc.getCentroid(), angleValues, radialResolution);
      
      candidates.stream().forEach(transform::addObservation);
      
      Set<ConnectedComponent> textCandidateCCs = new HashSet<>();  
      for (int thresh = 20; thresh > 2; thresh--)
      {
         Set<ConnectedComponent> textCC = findText(transform, thresh);
         if (textCC.isEmpty())
            continue;
         printIntermediateText(textCC, thresh);
         transform.remove(textCC);
         textCandidateCCs.addAll(textCC);
      }
      return textCandidateCCs;
   }

   /**
    * Filters the provided set of connected components based on area threshold metrics to 
    * remove components that are clearly non-textual. The removed components will include both 
    * noise and elements of other page elements (tables, illustrations, diagrams, etc).
    *  
    * @param ccSet An initial set of connected components to be analyzed. 
    * @param ratioThreshold The threshold to use for filtering based on aspect ratio.
    * @return A list of connected components that are candidate text elements.
    */
   public static List<ConnectedComponent> performAreaThresholding(
         Collection<ConnectedComponent> ccSet, int ratioThreshold)
   {
      // TODO separate into its own class?
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
      return candidates;
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

   private int rangeId = 0;
   private int stringId = 0;
   private int intermediateId = 0;
   
   /**
    * For each cell having a count greater than thresh in the transform, DO STUFF
    * @param transform
    * @param threshold The number of elements for the accumulators. 
    */
   private Set<ConnectedComponent> findText(HoughTransform<ConnectedComponent> transform, int threshold)
   {
      // TODO check out histogram equalization: http://homepages.inf.ed.ac.uk/rbf/HIPR2/histeq.htm referenced from http://homepages.inf.ed.ac.uk/rbf/HIPR2/hough.htm
      //      see also http://www.massey.ac.nz/~mjjohnso/notes/59318/l11.html
      
      Collection<HoughAccumulator<ConnectedComponent>> accumulators = transform.getAccumulators();
      List<HoughAccumulator<ConnectedComponent>> filtered = accumulators.parallelStream()
                     .filter(acc -> acc.size() > threshold)
                     .sorted((a, b) -> Integer.compare(a.size(), b.size()))
                     .collect(Collectors.toList());
      
      Set<ConnectedComponent> textChars = new HashSet<>();
      for (HoughAccumulator<ConnectedComponent> primayCell : filtered)
      {
         HoughPoint referencePoint = transform.getReferencePoint(primayCell);
         double theta = referencePoint.theta;

         // find all connnected components in a cluster of accumulators around the primary cells.
         List<ConnectedComponent> components = getClusteredComponents(proxy, transform, primayCell);
         components = components.stream()
                                .sorted(new HoughLineComparator(theta))
                                .collect(Collectors.toList());
         
         List<Phrase> textString = performStringSegmentation(components, theta);
         TextString str = new TextString(textString, theta);
         Set<ConnectedComponent> elements = str.getComponents();
         if (elements.isEmpty())
            continue;
         
//         printTextStrings(referencePoint, elements);
         textChars.addAll(elements);
      }
      
      return textChars;
   }

   private void printTextStrings(HoughPoint referencePoint, Collection<ConnectedComponent> elements)
   {
      stringId++;
      try
      {
         String strName = "   String " + stringId;
//            System.out.println(strName + ":  " + Math.round(Math.toDegrees(theta)));
         BufferedImage textCCImgs = CCWriter.render(elements, proxy.getWidth(), proxy.getHeight());
         textCCImgs = drawLine(textCCImgs, referencePoint);
         proxy.write(strName.trim(), "jpg", textCCImgs);
            
      }
      catch (IOException e)
      {
         // HACK Swallow exception in code used for debug. Auto-generated catch block
         e.printStackTrace();
      }
   }
   
   private void printIntermediateText(Collection<ConnectedComponent> elements, int iteration)
   {
      stringId++;
      try
      {
         String strName = "Intermediate " + iteration;
//            System.out.println(strName + ":  " + Math.round(Math.toDegrees(theta)));
         BufferedImage textCCImgs = CCWriter.render(elements, proxy.getWidth(), proxy.getHeight());
         proxy.write(strName.trim(), "jpg", textCCImgs);
            
      }
      catch (IOException e)
      {
         // HACK Swallow exception in code used for debug. Auto-generated catch block
         e.printStackTrace();
      }
   }

   /**
    * 
    * @param textCCImgs
    * @param primayCell
    * @param r
    */
   private BufferedImage drawLine(BufferedImage textCCImgs, HoughPoint p)
   {
      // TODO Auto-generated method stub
      double y0 = - 0 * Math.cos(p.theta) / Math.sin(p.theta) + p.rho / Math.sin(p.theta); 
      int width = textCCImgs.getWidth();
      double y1 = - width * Math.cos(p.theta) / Math.sin(p.theta) + p.rho / Math.sin(p.theta); 
      
      Graphics g = textCCImgs.getGraphics();
      g.setColor(Color.black);
      
      g.drawLine(0, (int)Math.round(y0), width, (int)Math.round(y1));
      g.dispose();
      
      return textCCImgs;
      
   }

   // HACK for temporary debug purposes.
   private void generateHoughClusterImage(HoughTransform<ConnectedComponent> transform, HoughAccumulator<ConnectedComponent> primaryCell, int size)
   {
      try
      {
         AngleColumn<ConnectedComponent> column = transform.getByAngle(primaryCell);

         int minRhoIx = primaryCell.getRhoIndex() - size;
         int maxRhoIx = primaryCell.getRhoIndex() + size;
         
         List<HoughAccumulator<ConnectedComponent>> accumulators = column.getRange(minRhoIx, maxRhoIx);
         List<ConnectedComponent> components = accumulators.stream()
               .flatMap(acc -> acc.getObservations().stream())
               .collect(Collectors.toList());
         
//         int min = Math.max(ix - fcluster, 0);
//         int max = Math.min(ix + fcluster, column.size() - 1);
//   
//         System.out.println("Selecting " + (max - min) + " accumulators [" + min + " - " + max + "] of " + column.size());
         BufferedImage textCCImgs = CCWriter.render(components, proxy.getWidth(), proxy.getHeight());
   
   
         HoughAccumulator<ConnectedComponent> acc = accumulators.get(0);
         HoughPoint ref = transform.getReferencePoint(acc);
         textCCImgs = drawLine(textCCImgs, ref);
   
         acc = accumulators.get(accumulators.size() - 1);
         ref = transform.getReferencePoint(acc);
         textCCImgs = drawLine(textCCImgs, ref);
   
         String strName = "In Range " + ++rangeId;
         proxy.write(strName.trim(), "jpg", textCCImgs);
   
      }
      catch (IOException e)
      {
         // HACK Swallow exception in code used for debug. Auto-generated catch block
         e.printStackTrace();
      }
   }

   private List<ConnectedComponent> getClusteredComponents(ImageProxy proxy, HoughTransform<ConnectedComponent> transform, HoughAccumulator<ConnectedComponent> primaryCell)
   {
      double R = transform.getRadialResolution();
      AngleColumn<ConnectedComponent> column = transform.getByAngle(primaryCell);
      
      HoughPoint referencePoint = transform.getReferencePoint(primaryCell);
      double theta = referencePoint.theta;
      boolean horizontal = isHorizontal(theta);
      
      int ix = column.indexOf(primaryCell);
      List<ConnectedComponent> components = getWindowedRange(column, primaryCell, 5);
      
      double avgHeight = components.stream()
            .mapToDouble(cc -> {
               BoundingBox box = cc.getBounds();
               return (horizontal) ? box.getHeight() : box.getWidth();
            }).sum() / components.size(); 
      int fcluster = (int)Math.ceil(avgHeight / R);
      
      components = getWindowedRange(column, primaryCell, fcluster);
//         if (components.isEmpty())
//            continue;
      
//      generateHoughClusterImage(transform, primaryCell, fcluster);
      return components;
   }

   /**
    * Returns the connected components for all cells within a specified range of a primary cell 
    * @param column
    * @param cellIx The cell at the center of the cluster.
    * @param size The numger of cells on either side to be returned.
    * @return
    */
   private List<ConnectedComponent> getWindowedRange(AngleColumn<ConnectedComponent> column, HoughAccumulator<ConnectedComponent> primayCell, int size)
   {
      int minRhoIx = primayCell.getRhoIndex() - size;
      int maxRhoIx = primayCell.getRhoIndex() + size;
      
      List<HoughAccumulator<ConnectedComponent>> accumulators = column.getRange(minRhoIx, maxRhoIx);
      List<ConnectedComponent> cluster = accumulators.stream()
            .flatMap(acc -> acc.getObservations().stream())
            .collect(Collectors.toList());
      
      return cluster;
   }

   private List<Phrase> performStringSegmentation(List<ConnectedComponent> components, double theta)
   {
      boolean horizontal = isHorizontal(theta);
      
      List<Group> groups = new ArrayList<>();
      List<Phrase> phrases = new ArrayList<>();
      
      // note we reference i+1, may need to guard 
      Group currentGroup = new Group(components.get(0));
      groups.add(currentGroup);
      
      List<BoundingBox> boxes = components.stream().map(cc -> cc.getBounds()).collect(Collectors.toList());
      Phrase currentPhrase = new Phrase(currentGroup);
      phrases.add(currentPhrase);
      for (int i = 1; i < components.size(); i++)
      {
         double height = computeHeight(components, i, horizontal, 5);
         double charGapThreshold = height;
         double wordGapThreshold = 2.5 * height;
         
         ConnectedComponent prevCC = currentGroup.getLast();
         ConnectedComponent nextCC = components.get(i);
         double distance = computeEdgeDistance(prevCC, nextCC, horizontal);
         
         if (distance <= charGapThreshold)
         {
            currentGroup.add(nextCC);
         } 
         else 
         {
            currentGroup = new Group(nextCC);
            groups.add(currentGroup);
            
            if (distance > wordGapThreshold)
            {
               currentPhrase = new Phrase(currentGroup);
               phrases.add(currentPhrase);
            }
         }
      }
      
      for (Iterator<Phrase> i = phrases.iterator(); i.hasNext(); )
      {
         Phrase p = i.next();
         // prune isolated words of size < N (n = 3)
         if (p.size() == 1)
         {
            Group grp = p.getWords().get(0);
            if (grp.size() < 3)        // HACK hard coded threshold.
               i.remove();
         }
         
         // TODO split phrases "as necessary"
      }
      
      return phrases;
   }

   private boolean isHorizontal(double theta)
   {
      boolean horizontal = theta > Math.PI / 4 && theta < 3 * Math.PI / 4;    // NOTE: theta is perpendicular to the line we are working with
      return horizontal;
   }

   /**
    * 
    * @param cc
    * @param nextCC
    * @param theta
    * @return May be negative in the case of overlapping components
    */
   private double computeEdgeDistance(ConnectedComponent cc, ConnectedComponent nextCC, boolean horizontal)
   {
      BoundingBox boxA = cc.getBounds();
      BoundingBox boxB = nextCC.getBounds();
      
      if (horizontal) {
         return boxA.getLeft() < boxB.getLeft()  
               ? boxB.getLeft() - boxA.getRight()     // box A is on the left 
               : boxA.getLeft() - boxB.getRight();    // box B is on the left
      } else {
         return boxA.getTop() < boxB.getTop()  
               ? boxB.getTop() - boxA.getBottom()     // box A is on the left 
               : boxA.getTop() - boxB.getBottom();    // box B is on the left
      }
   }

   /**
    * Computes the average height for a local cluster of components surrounding a 
    * specific component. Used to smooth out the variability for abnormally sized components 
    * (e.g. periods)
    * 
    * @param components An ordered list of components to consider.
    * @param ix The component at the center of the cluster.
    * @param horizontal {@code true} if the line is horizontally oriented
    * @param size The size of the local cluster. Should be odd. 
    * @return The average height
    */
   private double computeHeight(List<ConnectedComponent> components, int ix, boolean horizontal, int size)
   {
      int w = size / 2;
      int min = ix - w;
      int max = ix + w;
      
      if (min < 0)
      {
         min = 0;
         max = size;
      }
      
      if (max > components.size() - 1)
      {
         max = components.size() - 1;
         min = Math.max(max - size, 0);
      }
      
      int sum = 0;
      for (int i = min; i <= max; i++)
      {
         BoundingBox box = components.get(i).getBounds();
         sum += (horizontal) ? box.getHeight() : box.getWidth();
      }
      
      return (double)sum / (max - min + 1);
   }

   public static class HoughLineComparator implements Comparator<ConnectedComponent>
   {
      
      private double theta;
      private double xterm;
      private double yterm;
      private boolean horizontal;

      public HoughLineComparator(double theta)
      {
         this.theta = theta;
         this.xterm = Math.sin(theta);
         this.yterm = Math.cos(theta);
         horizontal = theta > Math.PI / 4 && theta < 3 * Math.PI / 4;
      }

      @Override
      public int compare(ConnectedComponent cc1, ConnectedComponent cc2)
      {

         BoundingBox boxA = cc1.getBounds();
         BoundingBox boxB = cc2.getBounds();
         if (horizontal) {
            return Integer.compare(boxA.getLeft(), boxB.getLeft());  
         } else {
            return Integer.compare(boxA.getTop(), boxB.getTop()); 
         }
//         Point a = cc1.getCentroid();
//         Point b = cc2.getCentroid();
//         double projectionA = a.getX() * xterm + a.getY() * yterm;
//         double projectionB = b.getX() * xterm + b.getY() * yterm;
         
//         return Doubles.compare(projectionA, projectionB);
      }
   }
   
   public static class Group
   {
      enum Type { isolated, phrase, word }
      
      Type type;
      List<ConnectedComponent> members = new ArrayList<>();
      
      public Group(ConnectedComponent cc)
      {
         type = Type.isolated;
         members.add(cc);
      }
      
      public void add(ConnectedComponent cc)
      {
         members.add(cc);
         
         if (type == Type.isolated)
            type = Type.word;
      }
      
      public ConnectedComponent getLast()
      {
         return members.get(members.size() - 1);
      }
      
      public int size()
      {
         return members.size();
      }
      
      public List<ConnectedComponent> getCharacters()
      {
         return Collections.unmodifiableList(members);
      }
   }
   
   public static class Phrase
   {
      List<Group> groups = new ArrayList<>();
      
      public Phrase(Group grp)
      {
         groups.add(grp);
      }
      
      public void add(Group grp)
      {
         groups.add(grp);
      }
      
      public int size()
      {
         return groups.size();
      }
      
      public List<Group> getWords() 
      {
         return Collections.unmodifiableList(groups);
      }
   }
   
   public static class TextString
   {
      private final List<Phrase> phrases;
      private Set<ConnectedComponent> components;
      private double theta;
      
      public TextString(List<Phrase> phrases, double theta)
      {
         this.phrases = phrases;
         this.theta = theta;
         
         components = phrases.stream()
               .flatMap(p -> p.getWords().stream())
               .flatMap(word -> word.getCharacters().stream())
               .collect(Collectors.toSet());
      }
      
      public Set<ConnectedComponent> getComponents()
      {
         return Collections.unmodifiableSet(components); 
      }
      
      public int getNumberOfComponents()
      {
         return components.size();
      }
      
   }
   
   private double[] generateHorizontalAngles()
   {
      List<Double> angles = new ArrayList<>();
      double aRes = Math.PI / 180;     // HACK hard code one degree angular resolution
      double range = Math.PI / 36;     // 5 degrees
//      for (double theta = 0; theta < Math.PI; theta += aRes)
//      {
//         angles.add(Double.valueOf(theta));
//      }
      
//      for (double theta = 0; theta < range; theta += aRes)
//      {
//         angles.add(Double.valueOf(theta));
//      }
//      
      for (double theta = (halfPi - range); theta < (halfPi + range); theta += aRes)
      {
         angles.add(Double.valueOf(theta));
      }
//      
      double[] angleValues = angles.parallelStream().mapToDouble(Double::doubleValue).toArray();
      return angleValues;
   }
   
   private double[] generateAllAngles()
   {
      double aRes = Math.PI / 180;     // HACK hard code one degree angular resolution
      List<Double> angles = new ArrayList<>();
      for (double theta = 0; theta < Math.PI; theta += aRes)
      {
         angles.add(Double.valueOf(theta));
      }
      
      return angles.parallelStream().mapToDouble(Double::doubleValue).toArray();
   }
   
   private double[] generateVerticalAngles()
   {
      double aRes = Math.PI / 180;     // HACK hard code one degree angular resolution
      double range = Math.PI / 36;     // 5 degrees

      List<Double> angles = new ArrayList<>();
      for (double theta = 0; theta < range; theta += aRes)
      {
         angles.add(Double.valueOf(theta));
      }
      return angles.parallelStream().mapToDouble(Double::doubleValue).toArray();
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
