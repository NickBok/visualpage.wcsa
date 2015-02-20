package edu.tamu.tcat.visualpage.wcsa;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;

import edu.tamu.tcat.analytics.datatrax.ResultsCollector;
import edu.tamu.tcat.analytics.datatrax.ResultsCollector.TranformationResult;
import edu.tamu.tcat.analytics.datatrax.ResultsCollector.TransformationError;
import edu.tamu.tcat.dia.binarization.BinaryImage;
import edu.tamu.tcat.dia.classifier.music.runlength.EM.Cluster;
import edu.tamu.tcat.visualpage.wcsa.importer.ImageProxy;

class Postprocessor implements ResultsCollector<BufferedImage>
   {

      private final ImageProxy proxy;
      private Map<UUID, String> outputIds;

      public Postprocessor(ImageProxy proxy, Map<UUID, String> outputIds)
      {
         this.proxy = proxy;
         this.outputIds = outputIds;
      }

      @Override
      public void handleResult(TranformationResult<BufferedImage> result)
      {
         //         egisterOutput(initialReducer, "initial_reduction");
         //         registerOutput(expander, "seed");
         //         registerOutput(finalExpander, "image_mask");
         try 
         {
            BinaryImage image;
            String key = outputIds.get(result.getKey().getSourceId());
            Object value = result.getValue();
            switch (key)
            {
               case "initial_reduction":
                  image = (BinaryImage)value;
//                     proxy.write("initial_reduction", BinaryImage.toBufferedImage(image));
                  break;
               case "rl_ratios":
                  @SuppressWarnings("unchecked")
                  Map<Integer, Set<Cluster>> ratios = (Map<Integer, Set<Cluster>>)value;
                  ratios.keySet().parallelStream()
                           .forEach((ix) -> {
                              Set<Cluster> clusters = ratios.get(ix.intValue());
                              Set<Long> means = clusters.parallelStream()
                                    .map(Cluster::mean)
                                    .map(Math::round)
                                    .collect(Collectors.toSet());
                              
                              System.out.println(proxy.getFilename() + "\tLine Number [" + ix + "]: \t" + Joiner.on(", ").join(means));
                           } );
                  
                  break;
               case "seed":
                  image = (BinaryImage)value;
//                     proxy.write("seed", BinaryImage.toBufferedImage(image));
                  break;
               case "image_mask":
//                  System.out.println(++ctProc + ":\t" + proxy.getFilename());
                  image = (BinaryImage)value;

                  // find percentage of black pixels
                  int ct = 0;
                  int size = image.getSize();
                  for (int ix = 0; ix < size; ix++)
                  {
                     if (image.isForeground(ix))
                        ct++;
                  }

                  // TODO probably more correctly either 
                  //      a) learn a metric or 
                  //      b) use ratio of initial foreground to image foreground
                  double foregroundPercentage = (double)ct/size;
                  if (foregroundPercentage > .02)
                  {
                     System.out.println(proxy.getFilename() + ": " + foregroundPercentage);
                     proxy.write("image_mask", BinaryImage.toBufferedImage(image));
                  }
                  break;
               default:
                  System.out.println("Unexpected result: " + result.getKey());
            }
         }
         catch (Exception ex)
         {
            System.err.println(ex);
            ex.printStackTrace();
         }
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