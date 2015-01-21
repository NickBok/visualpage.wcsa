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

public class DirectoryImporter
{

   private Path path;
   private Set<Path> imageFiles;
   private Iterator<Path> imIterator;
   private HashSet<String> suffixes;

   public DirectoryImporter(Path path)
   {
      this.path = path;
   }
   
   public void initialize()
   {
      suffixes = new HashSet<>();
      suffixes.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));
      
      try (Stream<Path> files = Files.walk(path))
      {
         imageFiles = files.filter(this::isImageFile)
                           .collect(Collectors.toSet());
         imIterator = imageFiles.iterator();
      }
      catch (IOException e)
      {
         throw new IllegalStateException("Failed to read image files.", e);
      }
   }
   
   public boolean hasNext() 
   {
      return imIterator.hasNext();
   }
   
   public String next() throws IOException
   {
      Path next = imIterator.next();
      return next.toString();
//      return ImageIO.read(next.toFile());
   }
   
   private boolean isImageFile(Path p)
   {
      if (!Files.isReadable(p))
         return false;
      
      String fName = p.getFileName().toString();
      int ix = fName.lastIndexOf(".");
      
      String suffix = (ix > 0) ? fName.substring(ix + 1) : "";
      return !suffix.isEmpty() && suffixes.contains(suffix);
   }
}
