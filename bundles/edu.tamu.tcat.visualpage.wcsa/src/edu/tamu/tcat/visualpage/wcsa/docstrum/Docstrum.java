package edu.tamu.tcat.visualpage.wcsa.docstrum;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
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
import edu.tamu.tcat.dia.segmentation.cc.twopass.UnionFind;
import edu.tamu.tcat.osgi.config.ConfigurationProperties;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;
import edu.tamu.tcat.visualpage.wcsa.Polynomial;
import edu.tamu.tcat.visualpage.wcsa.docstrum.ComponentNeighbors.AdjacentCC;
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
         
         AngleHistogram angleHistogram = AngleHistogram.create(adjTable);
         
         // estimate spacing using angle histogram
         Set<AdjacentCC> withinLine = adjTable.stream()
               .flatMap(adj -> adj.neighbors.stream())
               .filter(angleHistogram::isWithinLine)
               .collect(Collectors.toSet());
         double withinLineSpacing = estimateSpacing(withinLine, 2, 2);
         
         Set<AdjacentCC> betweenLine = adjTable.stream()
               .flatMap(adj -> adj.neighbors.stream())
               .filter(angleHistogram::isBetweenLine)
               .collect(Collectors.toSet());
         double betweenLineSpacing = estimateSpacing(betweenLine, 2, 2);
         System.out.println("   Within Line Spacing: " + withinLineSpacing);
         System.out.println("  Between Line Spacing: " + betweenLineSpacing);

         // TODO identify lines 
         Collection<Line> lines = findLines(adjTable, angleHistogram, 25000);
         
         start = System.currentTimeMillis();
         proxy.write("angles", angleHistogram.plot());
         renderOutputImages(proxy, image, ccSet, adjTable, angleHistogram, lines);
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
   
   /**
    * 
    * @param ccPairs Pairs of connected components connected by a link that is 'within line'
    */
   public Collection<Line> findLines(Set<ComponentNeighbors> adjTable, AngleHistogram angleHist, int maxSize)
   {
      // NOTE this impl is pretty awkward.
      // indexed by cc seq number, values are the UF set id for the corresponding lines.
      int[] labels = new int[maxSize];
      ConnectedComponent[] components = new ConnectedComponent[maxSize];
      Arrays.fill(labels, -1);
      UnionFind uf = new UnionFind(maxSize);
      for (ComponentNeighbors ccNeighbors : adjTable)
      {
         ConnectedComponent cc = ccNeighbors.cc;
         int srcSetId = getSetId(cc, uf, labels);
         components[cc.getSequence()] = cc;
         
         for (AdjacentCC adjCC : ccNeighbors.neighbors)
         {
            if (angleHist.isWithinLine(adjCC))
            {
               int destSet = getSetId(adjCC.cc, uf, labels);
               uf.union(srcSetId, destSet);
            }
         }
      }
      
      int lineId = 0;
      Map<Integer, Line> lines = new HashMap<>();     // map of set id to line
      for (int ccSeq = 0; ccSeq < labels.length; ccSeq++)
      {
         int setId = labels[ccSeq];
         if (setId < 0)
            continue;      // shouldn't happen?
         
         setId = uf.find(setId);    // get the canonical id for this set
         Line line = lines.get(Integer.valueOf(setId));
         if (line == null)
         {
            line = new Line();
            line.setId = setId;
            line.sequence = lineId++;
            lines.put(Integer.valueOf(setId), line);
         }
         
         line.components.add(components[ccSeq]);
      }
      
      lines.values().stream().forEach(Line::init);
      return lines.values();
   }

   private int getSetId(ConnectedComponent cc, UnionFind uf, int[] labels)
   {
      int srcIx = cc.getSequence();
      if (labels[srcIx] < 0)
         labels[srcIx] = uf.makeSet();
      return labels[srcIx];
   }

   /**
    * 
    * @param withinLine The set of adjacent connected components between which to compute
    *       the distance. 
    * @param resolution Resolution in pixels for the histogram. 
    * @param ts smoothing tolerance that is smaller than the closest expected peak spacing.
    * @throws IOException 
    */
   private double estimateSpacing(Set<AdjacentCC> withinLine, int resolution, int ts) throws IOException
   {
      double[] distances = withinLine.parallelStream()
               .mapToDouble(adjCC -> adjCC.dist).toArray();
      DoubleSummaryStatistics stats = DoubleStream.of(distances)
            .collect(DoubleSummaryStatistics::new,
                     DoubleSummaryStatistics::accept,
                     DoubleSummaryStatistics::combine);
      
      double min = stats.getMin();
      int nbins = ((int)(stats.getMax() - min) / resolution) + 1;
      int[] histogram = DoubleStream.of(distances).collect(
            () -> new int[nbins], 
            (histogramMemo, dist) -> { 
               int ix = (int)Math.floor((dist - min) / resolution);
               histogramMemo[ix] = histogramMemo[ix] + 1;
            }, 
            (a, b) -> IntStream.range(0, nbins).parallel().map(i -> a[i] + b[i]).toArray()
      );
      
      // compute smoothed histogram
      // compute window size
      int windowSize = resolution * (2 * ts + 1);
      int paddingSize = windowSize / 2;
      if (nbins < windowSize)
         return stats.getAverage();

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
      double denominator = withinLine.size() * windowSize;  
      double[] smoothed = IntStream.range(0,  nbins)
            .parallel()
            .mapToDouble(i -> (iHistogram[i + windowSize] - iHistogram[i]) / denominator)
            .toArray();
      
      int maxIx = IntStream.range(0,  nbins).reduce((memo, i) -> smoothed[memo] > smoothed[i] ? memo : i).orElse(-1);
      // convert value back to distance est.
      double dist = maxIx * resolution + min;
      return dist;
      
   }

   public BufferedImage plot(double[] histogram, int ix)
   {
      // HACK this is terrible, but OK for basic vis.
      // TODO need to allow for these to correspond to real values.
      int width = histogram.length;
      int height = 400;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
      WritableRaster raster = image.getRaster();      // TODO use a Graphics2D?
      initializeBackground(raster);
   
      Graphics2D g = image.createGraphics();
      g.setColor(Color.black);
      int barWidth = 1; //width / histogram.length;
      
      for (int x = 1; x < histogram.length; x++)
      {
         double y = histogram[x];
         
         int a = (int)(y * height * 20);
         int barHeight = Math.min(a, height);      // HACK: scale by 20 for better display

         g.fillRect(x, height - barHeight, barWidth, barHeight);
      }
      
      g.drawLine(ix, 0, ix, height);
      
      image.flush();
      return image;
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
   
   /**
    * Normalizes the histogram to be in the range {@code [0..1]} and applies a rectangular 
    * smoothing window of size {@code alpha * histogram.length}. 
    * 
    * @param histogram The raw counts to be smoothed and normalized.
    * @param alpha The window size as a percentage of the histogram length.
    * @param numElements The number of elements in the input collection. Equivalent to sum(histogram)
    * @return The smoothed, normalized histogram.
    */
   private static double[] smoothAndNormalize(int[] histogram, int windowSize, int numElements)
   {
      int nbins = histogram.length;
//      int paddingSize = (int)Math.floor(alpha * nbins / 2);
//      int windowSize = 2 * paddingSize;
      int paddingSize = (int)Math.floor((double)windowSize / 2);  // if odd, we ignore the position at the middle
      
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
   
 

   /**
    * Prints images for display/inspection purposes
    * @param proxy
    * @param image
    * @param ccSet
    * @param adjTable
    * @param angleHistogram 
    * @param lines 
    * @throws IOException
    */
   private void renderOutputImages(ImageProxy proxy, BufferedImage image, Set<ConnectedComponent> ccSet, Set<ComponentNeighbors> adjTable, AngleHistogram angleHistogram, Collection<Line> lines) throws IOException
   {
      BufferedImage renderCCs = CCWriter.render(ccSet, image.getWidth(), image.getHeight());
      proxy.write("docstrum", plot(adjTable));
      
//      renderCCs = renderAdjacencyTable(renderCCs, adjTable, angleHistogram);
//      proxy.write("colorized", renderCCs);
      
      renderCCs = renderLines(renderCCs, lines);
      proxy.write("lines", renderCCs);
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
    * @param hist 
    * @throws IOException
    */
   private BufferedImage renderLines(BufferedImage renderCCs, Collection<Line> lines) throws IOException
   {
      Graphics g = renderCCs.getGraphics();
      g.setColor(Color.black);
      
      lines.stream()
      .forEach(line -> {
         line.drawBox(g);
         line.drawCenterLine(g);
      });
      g.dispose();
      return renderCCs;
   }
   
   /**
    * Writes bounding boxes for connected components and lines connecting them on the supplied image.
    * 
    * @param proxy
    * @param renderCCs
    * @param adjTable
    * @param hist 
    * @throws IOException
    */
   private BufferedImage renderAdjacencyTable(BufferedImage renderCCs, Set<ComponentNeighbors> adjTable, AngleHistogram hist) throws IOException
   {
      Graphics g = renderCCs.getGraphics();
      g.setColor(Color.black);
      
      adjTable.stream()
      .forEach(adj -> {
         BoundingBox box = adj.cc.getBounds();
         Point c1 = adj.cc.getCentroid();
         g.drawRect(box.getLeft(), box.getTop(), box.getWidth(), box.getHeight());
         
         if (hist.valid)
         {
         adj.neighbors.stream()
            .forEach(adjCC -> {
               if (hist.isBetweenLine(adjCC))
               {
                  Point c2 = adjCC.cc.getCentroid();
                  g.drawLine(c1.getX(), c1.getY(), c2.getX(), c2.getY());
               }
            });
         }
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

   static void initializeBackground(WritableRaster raster)
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
