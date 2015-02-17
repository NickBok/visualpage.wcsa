package edu.tamu.tcat.visualpage.wcsa.importer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public class ImageProxy
{
   // we'll add info gathered from the source metadata over time.
   private final Path inputFile;
   private final DirectoryImporter importer;
   
   public ImageProxy(DirectoryImporter importer, Path input)
   {
      this.importer = importer;
      this.inputFile = input;
   }
   
   public BufferedImage getImage()
   {
      try
      {
         return ImageIO.read(inputFile.toFile());
      }
      catch (IOException e)
      {
         throw new IllegalStateException("Failed to load image [" + inputFile + "]", e);
      }
   }

   public String getFilename()
   {
      return inputFile.getFileName().toString();
   }
   
   public void write(String name, BufferedImage image) throws IOException
   {
      Path dir = importer.getOutputPath(this);
      if (!Files.exists(dir))
         Files.createDirectories(dir);
      
      Path outfile = dir.resolve(name + ".png");
      ImageIO.write(image, "png", outfile.toFile());
   }
}