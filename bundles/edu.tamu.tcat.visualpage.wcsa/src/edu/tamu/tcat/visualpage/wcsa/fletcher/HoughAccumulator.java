package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// intended for internal use
public class HoughAccumulator<T>
{
   private final int angleIx;
   private final int rhoIx;
   private Set<T> observations = new HashSet<T>();
   
   public HoughAccumulator(Integer rhoIx, Integer angleIx)
   {
      this.rhoIx = rhoIx.intValue();
      this.angleIx = angleIx.intValue();
   }
   
   
   public int size()
   {
      return observations.size();
   }

   public int getRhoIndex()
   {
      return rhoIx;
   }
   
   public int getAngleIndex()
   {
      return this.angleIx;
   }
   
   public void add(T observation)
   {
      observations.add(observation);
   }
   public void remove(T observation)
   {
      observations.remove(observation);
   }
   
   public Collection<T> getObservations()
   {
      return Collections.unmodifiableCollection(observations);
   }
}