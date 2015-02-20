package edu.tamu.tcat.visualpage.wcsa;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import com.google.common.base.Joiner;

import edu.tamu.tcat.analytics.datatrax.DataTraxFacade;
import edu.tamu.tcat.analytics.datatrax.DataValueKey;
import edu.tamu.tcat.analytics.datatrax.FactoryUnavailableException;
import edu.tamu.tcat.analytics.datatrax.ResultsCollector;
import edu.tamu.tcat.analytics.datatrax.TransformerConfigurationException;
import edu.tamu.tcat.analytics.datatrax.TransformerRegistration;
import edu.tamu.tcat.analytics.datatrax.TransformerRegistry;
import edu.tamu.tcat.analytics.datatrax.WorkflowController;
import edu.tamu.tcat.analytics.datatrax.config.DataInputPin;
import edu.tamu.tcat.analytics.datatrax.config.TransformerConfigEditor;
import edu.tamu.tcat.analytics.datatrax.config.TransformerConfiguration;
import edu.tamu.tcat.analytics.datatrax.config.WorkflowConfiguration;
import edu.tamu.tcat.analytics.datatrax.config.WorkflowConfigurationBuilder;
import edu.tamu.tcat.analytics.datatrax.config.WorkflowConfigurationException;
import edu.tamu.tcat.analytics.image.integral.datatrax.BufferedImageAdapter;
import edu.tamu.tcat.dia.adapters.opencv.BinaryToOpenCvMatrix;
import edu.tamu.tcat.dia.adapters.opencv.OpenCvMatrixToBinary;
import edu.tamu.tcat.dia.binarization.BinaryImage;
import edu.tamu.tcat.dia.binarization.sauvola.FastSauvolaTransformer;
import edu.tamu.tcat.dia.classifier.music.runlength.EM.Cluster;
import edu.tamu.tcat.dia.classifier.music.runlength.RunLengthRatioTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvClosingTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvErosionTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvOpeningTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.BloombergUnionTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.ExpansionTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.ThresholdReducerTransformer;
import edu.tamu.tcat.osgi.config.ConfigurationProperties;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;
import edu.tamu.tcat.visualpage.wcsa.importer.DirectoryImporter;
import edu.tamu.tcat.visualpage.wcsa.importer.ImageProxy;
import edu.tamu.tcat.visualpage.wcsa.internal.Activator;

public class Application implements IApplication
{

   @Override
   public Object start(IApplicationContext context) throws Exception
   {
      System.out.println("Hello World!");

      Map<UUID, String> outputIds = new HashMap<>();

      try (ServiceHelper helper = new ServiceHelper(Activator.getDefault().getContext()))
      {
         ConfigurationProperties properties = helper.waitForService(ConfigurationProperties.class, 10_000);
         DataTraxFacade dataTrax = helper.waitForService(DataTraxFacade.class, 10_000);
         long start = System.currentTimeMillis();
         DirectoryImporter importer = getImporter();
         BloombergWorkflowBuilder workflowBuilder = new BloombergWorkflowBuilder(dataTrax, outputIds);
         WorkflowController workflow = workflowBuilder.build();
         while (importer.hasNext())
         {
            ImageProxy proxy = importer.next();
            workflow.process(proxy::getImage, new Postprocessor(proxy, outputIds));
         }
//         // TODO process results
//         // TODO write outputs

         workflow.join(2, TimeUnit.HOURS);
         long end = System.currentTimeMillis();
         System.out.println("end: " + ((end - start) / (double)1000));
         return IApplication.EXIT_OK;
      } 
      catch (Exception ex)
      {
         ex.printStackTrace();
         return IApplication.EXIT_OK;
      }
   }
   
   String outputDir = "I:\\Projects\\HathiTrust WCSA\\output";
   String baseDir = "I:\\Projects\\HathiTrust WCSA\\WCSA initial small dataset";
   String itemDir = "ark+=13960=t00z72x8w";
//   String itemDir = "39015049753844";
   
   private DirectoryImporter getImporter()
   {
      
      Path root = Paths.get(baseDir).resolve(itemDir);
      Path output = Paths.get(outputDir).resolve(itemDir);
      DirectoryImporter importer = new DirectoryImporter(root, output);
      
      return importer;
   }

   @Override
   public void stop()
   {
      // TODO Auto-generated method stub
   }

   private static volatile int ctProc = 0;
   private static class Postprocessor implements ResultsCollector<BufferedImage>
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

   public static class BloombergWorkflowBuilder 
   {
      private DataTraxFacade facade;
      private WorkflowConfigurationBuilder builder;
      private TransformerRegistry registry;
      private Map<UUID, String> outputMap;

      BloombergWorkflowBuilder(DataTraxFacade facade, Map<UUID, String> outputMap)
      {
         this.facade = facade;
         this.outputMap = outputMap;
         builder = facade.createConfiguration();
         registry = facade.getTranformerRegistry();
      }
      
      private WorkflowController build() throws WorkflowConfigurationException, FactoryUnavailableException, TransformerConfigurationException
      {
         builder.setInputType(BufferedImage.class);
         DataValueKey inputKey = builder.getInputKey();
         
         // NOTE morphological operations are inverted due to foreground color 
         TransformerConfiguration integralImageAdapter = createIntegralImageAdapter(inputKey);
         TransformerConfiguration thresholder = createThresholder(integralImageAdapter);
         TransformerConfiguration runlength = createRLRatioCalculator(thresholder);
         TransformerConfiguration initialReducer = createReducer(thresholder, 3, 2);               // reduce T=1 (2x)
         TransformerConfiguration t4Reducer = createReducer(initialReducer, 4, 1);                 // reduce T=4 
         TransformerConfiguration t3Reducer = createReducer(t4Reducer, 3, 1);                      // reduce T=3 
         TransformerConfiguration closer = adapt2Binary(createCloser(adapt2Matrix(t3Reducer), 5)); // Closing  with SE 5x5
         TransformerConfiguration expander = createExpander(closer, 2);                            // expand (2x)
         TransformerConfiguration union = createUnion(initialReducer, expander);                   // Union of overlapping components
         TransformerConfiguration eroder = adapt2Binary(createEroder(adapt2Matrix(union), 3));     // Erode  with SE 3x3
         TransformerConfiguration finalExpander = createExpander(eroder, 2);                       // expand (2x)
         // TODO somewhere in here just output connected components
         
         registerOutput(initialReducer, "initial_reduction");
         registerOutput(runlength, "rl_ratios");
         registerOutput(expander, "seed");
//         registerOutput(finalExpander, "image_mask");
         registerOutput(eroder, "image_mask");
         
         WorkflowConfiguration config = builder.build();
         builder = null;
         return facade.createWorkflow(config);
      }

      private void registerOutput(TransformerConfiguration initialReducer, String name) throws WorkflowConfigurationException
      {
         UUID id = initialReducer.getId();
         builder.registerOutput(id);
         outputMap.put(id, name);
      }
      
      private TransformerConfiguration createIntegralImageAdapter(DataValueKey sourceData) 
            throws WorkflowConfigurationException, TransformerConfigurationException, FactoryUnavailableException
      {
         TransformerRegistration buffImageReg = registry.getRegistration(BufferedImageAdapter.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(buffImageReg);
         editor.setDataSource(buffImageReg.getDeclaredInput(BufferedImageAdapter.IMAGE_PIN), sourceData);
         TransformerConfiguration integralImageAdapter = editor.getConfiguration();
         
         return integralImageAdapter;
      }
      
      private TransformerConfiguration createThresholder(TransformerConfiguration integralImageDataSource) throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerConfigEditor editor;
         TransformerRegistration sauvolaReg = registry.getRegistration(FastSauvolaTransformer.EXTENSION_ID);
         
         editor = builder.createTransformer(sauvolaReg);
         editor.setDataSource(sauvolaReg.getDeclaredInput(FastSauvolaTransformer.INTEGRAL_IMAGE_PIN), integralImageDataSource);
         TransformerConfiguration thresholder = editor.getConfiguration();
         return thresholder;
      }
      
      private TransformerConfiguration createRLRatioCalculator(TransformerConfiguration thresholdedImage) throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerConfigEditor editor;
         TransformerRegistration rlRatioReg = registry.getRegistration(RunLengthRatioTransformer.EXTENSION_ID);
         
         editor = builder.createTransformer(rlRatioReg);
         editor.setParameter(RunLengthRatioTransformer.PARAM_ITERATIONS, Integer.valueOf(5));
         editor.setDataSource(rlRatioReg.getDeclaredInput(RunLengthRatioTransformer.BINARY_IMAGE_PIN), thresholdedImage);
         TransformerConfiguration thresholder = editor.getConfiguration();
         return thresholder;
      }
      
      private TransformerConfiguration createReducer(TransformerConfiguration binaryImageDataSource, int threshold, int iterations) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {

         TransformerRegistration reducerReg = registry.getRegistration(ThresholdReducerTransformer.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(reducerReg);
         editor.setDataSource(reducerReg.getDeclaredInput(ThresholdReducerTransformer.BINARY_IMAGE_PIN), binaryImageDataSource);
         editor.setParameter(ThresholdReducerTransformer.PARAM_T, threshold);
         editor.setParameter(ThresholdReducerTransformer.PARAM_ITERATIONS, iterations);
         
         TransformerConfiguration thresholder = editor.getConfiguration();
         return thresholder;
      }
      
      private TransformerConfiguration createExpander(TransformerConfiguration binaryImageDataSource, int iterations) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration expanderReg = registry.getRegistration(ExpansionTransformer.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(expanderReg);
         editor.setDataSource(expanderReg.getDeclaredInput(ExpansionTransformer.BINARY_IMAGE_PIN), binaryImageDataSource);
         editor.setParameter(ExpansionTransformer.PARAM_ITERATIONS, iterations);
         
         TransformerConfiguration thresholder = editor.getConfiguration();
         return thresholder;
      }
      
      private TransformerConfiguration createCloser(TransformerConfiguration matrixDataSource, int kernelSize) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration closingReg = registry.getRegistration(OpenCvOpeningTransformer.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(closingReg);
         DataInputPin inputPin = closingReg.getDeclaredInput(OpenCvClosingTransformer.IMAGE_MATRIX_PIN);
         editor.setDataSource(inputPin, matrixDataSource);
         editor.setParameter(OpenCvClosingTransformer.PARAM_KERNEL_SIZE, kernelSize);
         
         return editor.getConfiguration();
      }
      
      private TransformerConfiguration createUnion(TransformerConfiguration source, TransformerConfiguration seed) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration unionReg = registry.getRegistration(BloombergUnionTransformer.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(unionReg);
         editor.setDataSource(unionReg.getDeclaredInput(BloombergUnionTransformer.SOURCE_PIN), source);
         editor.setDataSource(unionReg.getDeclaredInput(BloombergUnionTransformer.SEED_PIN), seed);
         
         return editor.getConfiguration();
      }
      
      private TransformerConfiguration createEroder(TransformerConfiguration matrixDataSource, int kernelSize) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration erosionReg = registry.getRegistration(OpenCvErosionTransformer.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(erosionReg);
         DataInputPin inputPin = erosionReg.getDeclaredInput(OpenCvErosionTransformer.IMAGE_MATRIX_PIN);
         editor.setDataSource(inputPin, matrixDataSource);
         editor.setParameter(OpenCvErosionTransformer.PARAM_KERNEL_SIZE, kernelSize);
         
         return editor.getConfiguration();
      }
      
      private TransformerConfiguration adapt2Matrix(TransformerConfiguration binarySource) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration binary2mat = registry.getRegistration(BinaryToOpenCvMatrix.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(binary2mat);
         editor.setDataSource(binary2mat.getDeclaredInput(BinaryToOpenCvMatrix.BINARY_IMAGE_PIN), binarySource);
         
         return editor.getConfiguration();
      }
      
      private TransformerConfiguration adapt2Binary(TransformerConfiguration matrixSource) 
            throws FactoryUnavailableException, WorkflowConfigurationException, TransformerConfigurationException
      {
         TransformerRegistration mat2binary = registry.getRegistration(OpenCvMatrixToBinary.EXTENSION_ID);
         
         TransformerConfigEditor editor = builder.createTransformer(mat2binary);
         editor.setDataSource(mat2binary.getDeclaredInput(OpenCvMatrixToBinary.IMAGE_MATRIX_PIN), matrixSource);
         
         return editor.getConfiguration();
      }

   }

}
