package edu.tamu.tcat.visualpage.wcsa;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import edu.tamu.tcat.analytics.datatrax.DataTraxFacade;
import edu.tamu.tcat.analytics.datatrax.ResultsCollector;
import edu.tamu.tcat.analytics.datatrax.WorkflowController;
import edu.tamu.tcat.dia.binarization.BinaryImage;
import edu.tamu.tcat.osgi.config.ConfigurationProperties;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;
import edu.tamu.tcat.visualpage.wcsa.importer.DirectoryImporter;
import edu.tamu.tcat.visualpage.wcsa.importer.ImageProxy;
import edu.tamu.tcat.visualpage.wcsa.internal.Activator;

public class SimpleWCSATest
{
   private static final String ITEM_DIR_PARAM = "datatrax.importer.item.dir";
   private static final String BASE_DIR_PARAM = "datatrax.importer.base.dir";
   private static final String OUTPUT_DIR_PARAM = "datatrax.importer.output.dir";
   
   String outputDir = "I:\\Projects\\HathiTrust WCSA\\output";
   String baseDir = "I:\\Projects\\HathiTrust WCSA\\WCSA initial small dataset";
   String itemDir = "ark+=13960=t00z72x8w";
//   String itemDir = "39015049753844";
   
   public SimpleWCSATest()
   {
      // TODO Auto-generated constructor stub
   }
   
   public void execute() 
   {
      Map<UUID, String> outputIds = new HashMap<>();
      try (ServiceHelper helper = new ServiceHelper(Activator.getDefault().getContext()))
      {
         
         DataTraxFacade dataTrax = helper.waitForService(DataTraxFacade.class, 10_000);
         long start = System.currentTimeMillis();
         
         ConfigurationProperties properties = helper.waitForService(ConfigurationProperties.class, 10_000);
         DirectoryImporter importer = getImporter(properties);
         
         BloombergWorkflowBuilder workflowBuilder = new BloombergWorkflowBuilder(dataTrax, outputIds);
         WorkflowController workflow = workflowBuilder.build();
         while (importer.hasNext())
         {
            ImageProxy proxy = importer.next();
            Postprocessor postprocessor = new Postprocessor(proxy, outputIds);
            workflow.process(proxy::getImage, postprocessor);
         }
//         // TODO process results
//         // TODO write outputs

         workflow.join(2, TimeUnit.HOURS);
         long end = System.currentTimeMillis();
         System.out.println("end: " + ((end - start) / (double)1000));
      } 
      catch (Exception ex)
      {
         ex.printStackTrace();
      }
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
   class Postprocessor implements ResultsCollector<BufferedImage>
   {

      private final ImageProxy proxy;
      private Map<UUID, String> outputIds;
      
      // TODO make concurrent, make generic
      private final Map<String, Consumer<TranformationResult<BufferedImage>>> handlers = new HashMap<>();

      public Postprocessor(ImageProxy proxy, Map<UUID, String> outputIds)
      {
         this.proxy = proxy;
         this.outputIds = outputIds;
      }

      public void registerHandler(String key, Consumer<TranformationResult<BufferedImage>> handler)
      {
         handlers.put(key, handler);
      }
      
      
      @Override
      public void handleResult(TranformationResult<BufferedImage> result)
      {
    
         BinaryImage image;
         String key = outputIds.get(result.getKey().getSourceId());
         Object value = result.getValue();
         
            Consumer<TranformationResult<BufferedImage>> consumer = handlers.get(key);
            if (consumer != null)
            {
               try 
               {
                  consumer.accept(result);
               }
               catch (Exception ex)
               {
                  System.err.println(ex);
                  ex.printStackTrace();
               }
            }
//            switch (key)
//            {
//               case "initial_reduction":
//                  image = (BinaryImage)value;
////                     proxy.write("initial_reduction", BinaryImage.toBufferedImage(image));
//                  break;
//               case "rl_ratios":
//                  @SuppressWarnings("unchecked")
//                  Map<Integer, Set<Cluster>> ratios = (Map<Integer, Set<Cluster>>)value;
//                  ratios.keySet().parallelStream()
//                           .forEach((ix) -> {
//                              Set<Cluster> clusters = ratios.get(ix.intValue());
//                              Set<Long> means = clusters.parallelStream()
//                                    .map(Cluster::mean)
//                                    .map(Math::round)
//                                    .collect(Collectors.toSet());
//                              
//                              System.out.println(proxy.getFilename() + "\tLine Number [" + ix + "]: \t" + Joiner.on(", ").join(means));
//                           } );
//                  
//                  break;
//               case "seed":
//                  image = (BinaryImage)value;
////                     proxy.write("seed", BinaryImage.toBufferedImage(image));
//                  break;
//               case "image_mask":
////                  System.out.println(++ctProc + ":\t" + proxy.getFilename());
//                  image = (BinaryImage)value;
//
//                  // find percentage of black pixels
//                  int ct = 0;
//                  int size = image.getSize();
//                  for (int ix = 0; ix < size; ix++)
//                  {
//                     if (image.isForeground(ix))
//                        ct++;
//                  }
//
//                  // TODO probably more correctly either 
//                  //      a) learn a metric or 
//                  //      b) use ratio of initial foreground to image foreground
//                  double foregroundPercentage = (double)ct/size;
//                  if (foregroundPercentage > .02)
//                  {
//                     System.out.println(proxy.getFilename() + ": " + foregroundPercentage);
//                     proxy.write("image_mask", BinaryImage.toBufferedImage(image));
//                  }
//                  break;
//               default:
//                  System.out.println("Unexpected result: " + result.getKey());
//            }
//         }
//         catch (Exception ex)
//         {
//            System.err.println(ex);
//            ex.printStackTrace();
//         }
      }

      @Override
      public void handleError(TransformationError error)
      {
         System.err.println(error.getException());
      }

      @Override
      public void finished()
      {
//            System.out.println("done");
         // TODO Auto-generated method stub

      }
   }
}
