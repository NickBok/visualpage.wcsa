package edu.tamu.tcat.visualpage.wcsa.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

public class DirectoryImporter implements Iterator<ImageProxy>
{
   private Iterator<Path> imIterator;
   private Path outputPath;

   public DirectoryImporter(Path inputPath, Path outputPath)
   {
      this.outputPath = outputPath;
      
      Set<Path> imageFiles = ImageFileFilter.load(inputPath);
      imIterator = imageFiles.iterator();
   }
   
   @Override
   public boolean hasNext() 
   {
      return imIterator.hasNext();
   }
   
   @Override
   public ImageProxy next() 
   {
      return new ImageProxy(this, imIterator.next());
   }
   
   public Path getOutputPath(ImageProxy proxy)
   {
      String filename = proxy.getFilename();
      if (filename.lastIndexOf('.') > 0)
         filename = filename.substring(0, filename.lastIndexOf('.') + 1);
      
      Path imageOutputPath = outputPath.resolve(filename);
      return imageOutputPath;
   }
   
   private static class ImageFileFilter
   {
      private final static HashSet<String> suffixes = new HashSet<>();
      static {
         suffixes.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));
      }
      
      private static Set<Path> load(Path rootDir)
      {
         try (Stream<Path> files = Files.walk(rootDir))
         {
            return files.filter(ImageFileFilter::isImageFile)
                              .collect(Collectors.toSet());
         }
         catch (IOException e)
         {
            throw new IllegalStateException("Failed to read image files.", e);
         }
      }
      private static boolean isImageFile(Path p)
      {
         if (!Files.isReadable(p))
            return false;
         
         String fName = p.getFileName().toString();
         int ix = fName.lastIndexOf(".");
         
         String suffix = (ix > 0) ? fName.substring(ix + 1) : "";
         return !suffix.isEmpty() && suffixes.contains(suffix);
      }
   }
}
