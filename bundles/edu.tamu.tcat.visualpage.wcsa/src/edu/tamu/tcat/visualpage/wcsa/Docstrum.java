package edu.tamu.tcat.visualpage.wcsa;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import com.google.common.primitives.Doubles;

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
import edu.tamu.tcat.osgi.config.ConfigurationProperties;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;
import edu.tamu.tcat.visualpage.wcsa.Polynomial.CriticalPoint;
import edu.tamu.tcat.visualpage.wcsa.importer.DirectoryImporter;
import edu.tamu.tcat.visualpage.wcsa.importer.ImageProxy;
import edu.tamu.tcat.visualpage.wcsa.internal.Activator;

/**
 *  Placeholder class to test algorithm implementation. 
 */
public class Docstrum
{
   private static final String ITEM_DIR_PARAM = "datatrax.importer.item.dir";
   private static final String BASE_DIR_PARAM = "datatrax.importer.base.dir";
   private static final String OUTPUT_DIR_PARAM = "datatrax.importer.output.dir";
   
   private final FastSauvola binarizer;
   int minComponentSize = 128; // TODO: allow this to be set by callers (8x16 px)

   public Docstrum()
   {
      binarizer = new FastSauvola();
   }
   
   
   public void execute()
   {
      try (ServiceHelper helper = new ServiceHelper(Activator.getDefault().getContext()))
      {
         ConfigurationProperties properties = helper.waitForService(ConfigurationProperties.class, 10_000);
         Set<ImageProxy> images = loadImages(properties);

         for (ImageProxy p : images)
         {
            System.out.println("Analysing Image: " + p.getFilename());
            long start = System.currentTimeMillis();
            this.performDocstrum(p);
            long end = System.currentTimeMillis();
            
            System.out.println("    ---------------------------");
            System.out.println("    Elapsed Time: " + (end - start) + " ms\n");
         }
//         images.parallelStream().forEach(this::performDocstrum);
//         System.out.println(images);
         
      } 
      catch (Exception ex)
      {
         ex.printStackTrace();
      }
   }
   

   private void performDocstrum(ImageProxy proxy)
   {
      // 1. Read, threshold the image, extract connected components
      long start = System.currentTimeMillis();
      BufferedImage image = proxy.getImage();
      long end = System.currentTimeMillis();
      System.out.println("  Image Load: " + (end - start) + " ms");
      
      try
      {
         Set<ConnectedComponent> ccSet = findConnectedComponents(image);
    
         if (ccSet.size() < 10)     // if fewer than 10 cc's assume page is blank.
            return;
         
         Set<ComponentNeighbors> adjTable = findNeighbors(ccSet, 5);
         
         // map theta from -PI to PI to - PI / 2 to PI / 2 
         computeAngleHistogram(proxy, adjTable);
         
         
//         double[] distances = adjTable.parallelStream()
//                              .flatMap(neighbors -> neighbors.neighbors.stream())
//                              .mapToDouble(adj -> adj.dist)
//                              .toArray();
//         
//         computeDistanceHistogram(distances, 1);
         
         start = System.currentTimeMillis();
         renderOutputImages(proxy, image, ccSet, adjTable);
         end = System.currentTimeMillis();
         System.out.println("  Write imgs: " + (end - start) + " ms");
      }
      catch (BinarizationException | IOException e)
      {
         // TODO Auto-generated catch block
         e.printStackTrace();
      }
      finally 
      {
         image.flush();
      }
   }




   private Set<ConnectedComponent> findConnectedComponents(BufferedImage image) throws BinarizationException
   {
      long start = System.currentTimeMillis();

      IntegralImage integralImage = IntegralImageImpl.create(image);
      BinaryImage binaryImage = binarizer.binarize(integralImage);
      ConnectedComponentFinder finder = new ConnectedComponentFinder(binaryImage, 100_000);
      ConnectComponentSet components = finder.call();

      Set<ConnectedComponent> ccSet = components.asSet().stream()
            .filter(cc -> cc.getBounds().getArea() > minComponentSize)   
            .collect(Collectors.toSet());

      long end = System.currentTimeMillis();
      System.out.println("    Find CCs: " + (end - start) + " ms");

      return ccSet;
   }


   /**
    * Find k nearest neighbors of each cc and compute angle and distance between.
    * @param ccSet
    * @param k
    * @return
    */
   private Set<ComponentNeighbors> findNeighbors(Set<ConnectedComponent> ccSet, int k)
   {
      long start = System.currentTimeMillis();
      Set<ComponentNeighbors> adjTable = ccSet.parallelStream()
            .map((ref) -> new ComponentNeighbors(ref, ccSet, k))
            .collect(Collectors.toSet());
      long end = System.currentTimeMillis();
      System.out.println("   Adj Table: " + (end - start) + " ms");
      
      return adjTable;
   }
   
   private static class AngleHistogram
   {
      double[] histogram;
      Polynomial fitHistogram;
      double orientation;
   }
   private void computeAngleHistogram(ImageProxy proxy, Set<ComponentNeighbors> adjTable) throws IOException
   {
      long start = System.currentTimeMillis();

      double halfPi = Math.PI / 2;
      double[] angles = adjTable.parallelStream()
                           .flatMap(neighbors -> neighbors.neighbors.stream())
                           .mapToDouble(adj -> (adj.theta > halfPi) ? adj.theta - Math.PI 
                                                   : (adj.theta < -halfPi) ? adj.theta + Math.PI 
                                                   : adj.theta)
                           .toArray();
      double[] h = computeAngleHistogram(angles, 360);          // HACK: hard coded 180 deg. at .5 degree resolution
      Polynomial bestFit = Polynomial.fit(h, 5);
      List<CriticalPoint> criticalPoints = bestFit.findCriticalPoints(0, h.length, 1);
      if (criticalPoints.size() < 3)
         throw new IllegalStateException();
      
      criticalPoints.get(0);
      CriticalPoint mainRotation = criticalPoints.get(1);
      criticalPoints.get(2);
      
      long end = System.currentTimeMillis();
      System.out.println("  Angle hist: " + (end - start) + " ms");
      
      start = System.currentTimeMillis();
      proxy.write("nnAngles", plotHistogram(h, bestFit));
      end = System.currentTimeMillis();
      System.out.println("   Plot hist: " + (end - start) + " ms");
   }


   /**
    * Prints images for display/inspection purposes
    * @param proxy
    * @param image
    * @param ccSet
    * @param adjTable
    * @throws IOException
    */
   private void renderOutputImages(ImageProxy proxy, BufferedImage image, Set<ConnectedComponent> ccSet, Set<ComponentNeighbors> adjTable) throws IOException
   {
      BufferedImage renderCCs = CCWriter.render(ccSet, image.getWidth(), image.getHeight());
      proxy.write("docstrum", plot(adjTable));
      
      renderCCs = renderAdjacencyTable(renderCCs, adjTable);
      proxy.write("colorized", renderCCs);
   }

   private double[] computeAngleHistogram(double[] angles, int nbins)
   {
      final double halfPi = Math.PI / 2;
      final double binSize = Math.PI / nbins; 
      int[] histogram = DoubleStream.of(angles).collect(
            () -> new int[nbins], 
            (histogramMemo, theta) -> { 
               // NOTE: rotate from [-PI/2, PI/2] to [0, PI] and linear map to [0, nbins)  
               //       mod by nbins to map 0 and PI to the same value (0)
               int ix = (int)Math.floor((theta + halfPi) / binSize);
               if (ix == nbins)
                  ix = 0;
               histogramMemo[ix] = histogramMemo[ix] + 1;
            }, 
            (a, b) -> {
               int[] hist = new int[nbins];
               for (int i = 0; i < nbins; i++)
               {
                  hist[i] = a[i] + b[i];
               }
            });
      
      
      double alpha = 0.25;    // size of smoothing window
      return smoothAndNormalize(histogram, alpha, angles.length);
   }

   /**
    * Normalizes the histogram to be in the range {@code [0..1]} and applies a rectangular 
    * smoothing window of size {@code alpha * histogram.length}. 
    * 
    * @param histogram The raw counts to be smoothed and normalized.
    * @param alpha The window size as a percentage of the histogram length.
    * @param numElements The number of elements in the input collection. Equivalent to sum(histogram)
    * @return The smoothed, normalized histogram.
    */
   private double[] smoothAndNormalize(int[] histogram, double alpha, int numElements)
   {
      int nbins = histogram.length;
      int paddingSize = (int)Math.floor(alpha * nbins / 2);
      int windowSize = 2 * paddingSize;
      
      // compute integral of the histogram with padding before and after to allow the 
      // histogram to wrap around the end
      int[] iHistogram = new int[nbins + windowSize];
      iHistogram[0] = histogram[nbins - paddingSize];
      for (int i = 1; i < iHistogram.length; i++)
      {
         int index = (nbins - paddingSize + i) % nbins; 
         iHistogram[i] = iHistogram[i - 1] + histogram[index];
      }
      
      // divide by window size for smoothing
      // divide by nbins to normalize histogram in range 0..1 
      double denominator = numElements * windowSize;  
      return IntStream.range(0,  nbins)
            .parallel()
            .mapToDouble(i -> (iHistogram[i + windowSize] - iHistogram[i]) / denominator)
            .toArray();
   }
   
   private double[] computeDistanceHistogram(double[] distances, int pixelsPerBin)
   {
      return new double[0];
//      throw new UnsupportedOperationException();
   }
   

   /**
    * Writes bounding boxes for connected components and lines connecting them on the supplied image.
    * 
    * @param proxy
    * @param renderCCs
    * @param adjTable
    * @throws IOException
    */
   private BufferedImage renderAdjacencyTable(BufferedImage renderCCs, Set<ComponentNeighbors> adjTable) throws IOException
   {
      Graphics g = renderCCs.getGraphics();
      g.setColor(Color.black);
      
      adjTable.stream()
      .forEach(adj -> {
         BoundingBox box = adj.cc.getBounds();
         Point c1 = adj.cc.getCentroid();
         g.drawRect(box.getLeft(), box.getTop(), box.getWidth(), box.getHeight());
         
         adj.neighbors.stream()
            .forEach(adjCC -> {
               Point c2 = adjCC.cc.getCentroid();
               g.drawLine(c1.getX(), c1.getY(), c2.getX(), c2.getY());
            });
      });
      g.dispose();
      return renderCCs;
   }

   

   private BufferedImage plotHistogram(double[] histogram, Polynomial eq)
   {
      int width = 400;
      int height = 400;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
      WritableRaster raster = image.getRaster();      // TODO use a Graphics2D?
      initializeBackground(raster);
   
      Graphics2D g = image.createGraphics();
      g.setColor(Color.black);
      int barWidth = width / histogram.length;
      
      for (Polynomial.CriticalPoint cp : eq.findCriticalPoints(0, 360, 1))
      {
         g.drawLine((int)cp.point, 0, (int)cp.point, height);
      }
      
      for (int x = 1; x < histogram.length; x++)
      {
         double y = eq.applyAsDouble(x);
         
         int a = (int)(y * height * 20);
         int barHeight = Math.min(a, height);      // HACK: scale by 20 for better display

         g.fillRect(x, height - barHeight, barWidth, barHeight);
      }
      
      
      image.flush();
      return image;
   }

   private BufferedImage plot(Set<ComponentNeighbors> adjTable)
   {
      List<ComponentNeighbors.AdjacentCC> pairs = adjTable.parallelStream()
            .flatMap(neighbors -> neighbors.neighbors.stream())
            .collect(Collectors.toList());
      
      // TODO create buffered image
      int width = 400;
      int height = 400;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
      WritableRaster raster = image.getRaster();      // TODO use a Graphics2D?
      initializeBackground(raster);

      pairs.forEach(pair -> {
         double x = pair.dist * Math.cos(pair.theta) + (width / 2);
         double y = pair.dist * Math.sin(pair.theta) + (height / 2);
         
         if (x < 0 || x >= width || y < 0 || y >= height)
            return;

         raster.setSample((int)x, (int)y, 0, 0);
      });
      
      image.flush();
      return image;

   }

   private static void initializeBackground(WritableRaster raster)
   {
      int width = raster.getWidth();
      int height = raster.getHeight();
      
      // TODO seems like there should be a faster/better way to do this.
      // should investigate the following
//      int[] rgbArray = new int[width * height];
//      Arrays.fill(rgbArray, 0xFFFFFFFF);
//      image.setRGB(0, 0, width, height, rgbArray, 0, width);

      // set to white background
      int bands = raster.getNumBands();
      for (int r = 0; r < height; r++)  {
         for (int c = 0; c < width; c++) {
            for (int b = 0; b < bands; b++) {
               raster.setSample(c, r, b, 255);
            }
         }
      }
   }
   
   public static class ComponentNeighbors
   {
      ConnectedComponent cc;
      List<AdjacentCC> neighbors = new ArrayList<>();
      
      ComponentNeighbors(ConnectedComponent ref, Set<ConnectedComponent> components, int k) 
      {
         this.cc = ref;
         final Point px = ref.getCentroid();
         SortedSet<ConnectedComponent> sorted = new TreeSet<>((a, b) -> {
            double aDist = distance(px, a.getCentroid());
            double bDist = distance(px, b.getCentroid());
            
            return Doubles.compare(aDist, bDist);
         });
         sorted.addAll(components);
         sorted.remove(ref);

         List<AdjacentCC> sortedNeighbors = sorted.stream().limit(k)
                  .map(cc -> {
                     Point centroid = cc.getCentroid();
                     
                     AdjacentCC adjacenctCC = new AdjacentCC();
                     adjacenctCC.cc = cc;
                     adjacenctCC.dist = distance(px, centroid);
                     adjacenctCC.theta = angle(px, centroid);
                     
                     return adjacenctCC;
                  })
                  .collect(Collectors.toList());
         
         this.neighbors = Collections.unmodifiableList(sortedNeighbors);
         
      }
      
      public static class AdjacentCC implements Comparable<AdjacentCC> 
      {
         ConnectedComponent cc;
         double dist;
         double theta;
         
         @Override
         public int compareTo(AdjacentCC other)
         {
            return Doubles.compare(dist, other.dist);
         }
      }
      
      private static double distance(Point a, Point b)
      {
         int x = a.getX() - b.getX();
         int y = a.getY() - b.getY();
         
         return Math.sqrt(x * x + y * y);
      }
      
      private static double angle(Point a, Point b)
      {
         // See
         // http://stackoverflow.com/questions/7586063/how-to-calculate-the-angle-between-a-line-and-the-horizontal-axis
         int x = b.getX() - a.getX();
         int y = b.getY() - a.getY();
         
         return Math.atan2(y, x);
      }
   }

   private Set<ImageProxy> loadImages(ConfigurationProperties properties)
   {
      DirectoryImporter importer = getImporter(properties);
      Set<ImageProxy> images = new TreeSet<ImageProxy>((a, b) ->
      {
         return a.getFilename().compareTo(b.getFilename());
      });
      
      while (importer.hasNext())
      {
         images.add(importer.next());
      }
      return images;
   }
   
   private DirectoryImporter getImporter(ConfigurationProperties properties)
   {
      String outputDir = properties.getPropertyValue(OUTPUT_DIR_PARAM, String.class); // "I:\\Projects\\HathiTrust WCSA\\output";
      String baseDir = properties.getPropertyValue(BASE_DIR_PARAM, String.class); //"I:\\Projects\\HathiTrust WCSA\\WCSA initial small dataset";
      String itemDir = properties.getPropertyValue(ITEM_DIR_PARAM, String.class); //"ark+=13960=t00z72x8w";
      
      Path root = Paths.get(baseDir).resolve(itemDir);
      Path output = Paths.get(outputDir).resolve(itemDir);
      DirectoryImporter importer = new DirectoryImporter(root, output);
      
      return importer;
   }

}
