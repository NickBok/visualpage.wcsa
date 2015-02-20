package edu.tamu.tcat.visualpage.wcsa;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class Application implements IApplication
{

   @Override
   public Object start(IApplicationContext context) throws Exception
   {

      try 
      {
//         SimpleWCSATest test = new SimpleWCSATest();
//         test.execute();
         return IApplication.EXIT_OK;
      } 
      catch (Exception ex)
      {
         ex.printStackTrace();
         return IApplication.EXIT_OK;
      }
   }

   

   @Override
   public void stop()
   {
      // TODO Auto-generated method stub
   }

}
