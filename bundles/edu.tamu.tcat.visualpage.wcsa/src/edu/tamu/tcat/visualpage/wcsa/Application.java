package edu.tamu.tcat.visualpage.wcsa;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import edu.tamu.tcat.analytics.datatrax.DataTraxFacade;
import edu.tamu.tcat.analytics.datatrax.WorkflowController;
import edu.tamu.tcat.analytics.datatrax.config.WorkflowConfigurationBuilder;
import edu.tamu.tcat.osgi.config.ConfigurationProperties;
import edu.tamu.tcat.osgi.services.util.ServiceHelper;
import edu.tamu.tcat.visualpage.wcsa.importer.DirectoryImporter;
import edu.tamu.tcat.visualpage.wcsa.internal.Activator;

public class Application implements IApplication
{

   @Override
   public Object start(IApplicationContext context) throws Exception
   {
         System.out.println("Hello World!");

         
      try (ServiceHelper helper = new ServiceHelper(Activator.getDefault().getContext()))
      {
         ConfigurationProperties properties = helper.waitForService(ConfigurationProperties.class, 10_000);
         DataTraxFacade dataTrax = helper.waitForService(DataTraxFacade.class, 10_000);
         
         DirectoryImporter importer = getImporter();
         WorkflowController workflow = getWorkflow(dataTrax);
         while (importer.hasNext())
         {
            System.out.println(importer.next());
         }
//         // TODO load image importer
//         // TODO load workflow
//         // TODO execute workflow
//         // TODO process results
//         // TODO write outputs

         return IApplication.EXIT_OK;
      } 
      catch (Exception ex)
      {
         ex.printStackTrace();
         return IApplication.EXIT_OK;
      }
   }
   
   private WorkflowController getWorkflow(DataTraxFacade dataTrax)
   {
      WorkflowConfigurationBuilder builder = dataTrax.createConfiguration();
      
      // TODO Auto-generated method stub
      return null;
   }

   private DirectoryImporter getImporter()
   {
      String baseDir = "I:\\Projects\\HathiTrust WCSA\\WCSA initial small dataset";
      String itemDir = "ark+=13960=t0xp6w53s";
      
      Path root = Paths.get(baseDir).resolve(itemDir);
      DirectoryImporter importer = new DirectoryImporter(root);
      importer.initialize();
      
      return importer;
   }

   @Override
   public void stop()
   {
      // TODO Auto-generated method stub
   }

}
