package edu.tamu.tcat.visualpage.wcsa.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

public class DirectoryImporter 
{
   private Path outputPath;
   private Path inputBase;
   private Set<Path> imageFiles = new HashSet<>();

   public DirectoryImporter(Path inputBase, Path outputPath)
   {
      this.inputBase = inputBase;
      this.outputPath = outputPath;
   }
   
   public void addDirectory(Path dir) throws IOException
   {
      Set<Path> imFiles = Files.walk(dir)
                               .filter(DirectoryImporter::isImageFile)
                               .collect(Collectors.toSet());
      imageFiles.addAll(imFiles);
   }
   
   public Set<ImageProxy> getProxies()
   {
      return imageFiles.parallelStream().map(p -> new ImageProxy(this, p)).collect(Collectors.toSet());
   }
   
   public Path getOutputPath(ImageProxy proxy)
   {
      String filename = proxy.getFilename();
      if (filename.lastIndexOf('.') > 0)
         filename = filename.substring(0, filename.lastIndexOf('.') + 1);
      
      Path relPath = inputBase.relativize(proxy.getPath().getParent());
      Path imageOutputPath = outputPath.resolve(relPath).resolve(filename);
      return imageOutputPath;
   }
   
   private final static HashSet<String> suffixes = new HashSet<>();
   static {
      suffixes.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));
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
