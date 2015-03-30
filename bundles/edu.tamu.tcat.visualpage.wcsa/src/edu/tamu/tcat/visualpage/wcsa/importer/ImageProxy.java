package edu.tamu.tcat.visualpage.wcsa.importer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public class ImageProxy implements AutoCloseable
{
   // we'll add info gathered from the source metadata over time.
   private final Path inputFile;
   private final DirectoryImporter importer;
   
   private BufferedImage image = null;
   private boolean closed = false;
   
   public ImageProxy(DirectoryImporter importer, Path input)
   {
      this.importer = importer;
      this.inputFile = input;
   }
   
   public synchronized BufferedImage getImage()
   {
      if (closed)
         throw new IllegalStateException("This image proxy has been closed");
      
      if (image != null)
         return image;
      
      try
      {
         image  = ImageIO.read(inputFile.toFile());
         return image;
      }
      catch (IOException e)
      {
         throw new IllegalStateException("Failed to load image [" + inputFile + "]", e);
      }
   }

   public synchronized void close()
   {
      if (image != null)
         image.flush();
      
      image = null;
      closed  = true;
   }
   
   public int getWidth()
   {
      return getImage().getWidth();
   }
   
   public int getHeight()
   {
      return getImage().getHeight();
   }
   
   public Path getPath()
   {
      return inputFile;
   }
   
   public String getFilename()
   {
      return inputFile.getFileName().toString();
   }
   
   /**
    * 
    * @param name The name of the file to write
    * @param fmt The format to write
    * @param image The image to write
    * @throws IOException
    */
   public void write(String name, String fmt, BufferedImage image) throws IOException
   {
      Path dir = importer.getOutputPath(this);
      if (!Files.exists(dir))
         Files.createDirectories(dir);
      
      Path outfile = dir.resolve(name + "." + fmt);
      ImageIO.write(image, fmt, outfile.toFile());
   }
}