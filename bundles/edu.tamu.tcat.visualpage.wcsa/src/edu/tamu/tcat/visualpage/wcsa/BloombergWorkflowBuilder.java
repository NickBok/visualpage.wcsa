package edu.tamu.tcat.visualpage.wcsa;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.UUID;

import edu.tamu.tcat.analytics.datatrax.DataTraxFacade;
import edu.tamu.tcat.analytics.datatrax.DataValueKey;
import edu.tamu.tcat.analytics.datatrax.FactoryUnavailableException;
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
import edu.tamu.tcat.dia.binarization.sauvola.FastSauvolaTransformer;
import edu.tamu.tcat.dia.classifier.music.runlength.RunLengthRatioTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvClosingTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvErosionTransformer;
import edu.tamu.tcat.dia.morphological.opencv.transformer.OpenCvOpeningTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.BloombergUnionTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.ExpansionTransformer;
import edu.tamu.tcat.dia.segmentation.images.bloomberg.datatrax.ThresholdReducerTransformer;

public class BloombergWorkflowBuilder 
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
      
      WorkflowController build() throws WorkflowConfigurationException, FactoryUnavailableException, TransformerConfigurationException
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