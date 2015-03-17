package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import edu.tamu.tcat.analytics.image.region.Point;

public class HoughTransform<T>
   {
      private Table<Integer, Integer, HoughAccumulator<T>> accumulator;
      private final Function<T, Point> fn;
      private final double[] angles;
      private final double radialResolution;
//      private SortedSet<Integer> rhoValues;
//      private SortedSet<Integer> thetaValues;
      
      /**
       * 
       * @param angularResolution 
       * @param radialResolution
       */
      public HoughTransform(Function<T, Point> fn, double angularResolution, double radialResolution)
      {
         
         this.fn = fn;
         accumulator = HashBasedTable.create();
         this.radialResolution = radialResolution;
         
         int ix = 0;
         int numAngles = (int)Math.ceil(Math.PI / angularResolution);
         angles = new double[numAngles];
         for (double theta = 0; theta < Math.PI; theta += angularResolution)
         {
            angles[ix++] = theta;
         }
      }

      public AngleColumn<T> getByAngle(HoughAccumulator<T> acc)
      {
         return new AngleColumn<T>(acc.getAngleIndex(), accumulator);
      }

      /**
       * 
       * @return accumulators uses to count values for individual rho, theta pairs
       *       within the resolution of this transform.
       */
      public Collection<HoughAccumulator<T>> getAccumulators()
      {
         return accumulator.cellSet().stream()
                     .map(cell -> cell.getValue())
                     .collect(Collectors.toList());
      }

      /**
       * 
       * @param fn
       * @param angles Must not be modified by caller after creation.
       * @param radialResolution
       */
      public HoughTransform(Function<T, Point> fn, double[] angles, double radialResolution)
      {
         
         this.fn = fn;
         this.angles = angles;
         this.accumulator = HashBasedTable.create();
         this.radialResolution = radialResolution;
      }
      
      public void addObservation(T observation)
      {
//         rhoValues = null;
//         thetaValues = null;
         
         Point p = fn.apply(observation);
         int x = p.getX();
         int y = p.getY();

         IntStream.range(0, angles.length).forEach(i -> {
            double theta = angles[i];
            double rho = x * Math.cos(theta) + y * Math.sin(theta);
            Integer rhoIx = Integer.valueOf((int)Math.floor(rho / radialResolution));
            
            HoughAccumulator<T> acc = accumulator.get(rhoIx, i);
            if (acc == null)
            {
               acc = new HoughAccumulator<T>(rhoIx, i);
               accumulator.put(rhoIx, i, acc);
            }
            
            acc.add(observation);
         });
      }
      
      private int toAngleIx(double theta)
      {
         int thetaIx = -1;
         double minDiff = Double.MAX_VALUE;
         for (int i = 0; i < angles.length; i++)
         {
            double diff = Math.abs(theta - angles[i]);
            if (diff < minDiff)
            {
               minDiff = diff;
               thetaIx = i;
            }
         }
         
         return thetaIx;
      }
      
      public HoughAccumulator<T> getCell(HoughPoint p)
      {
         int thetaIx = toAngleIx(p.theta);
         int rhoIx = Integer.valueOf((int)Math.floor(p.rho / radialResolution));
         
         // TODO check existence 
         return accumulator.get(Integer.valueOf(rhoIx), Integer.valueOf(thetaIx));
      }
      
      public HoughPoint getReferencePoint(HoughAccumulator<T> cell)
      {
         double rho = cell.getRhoIndex() * radialResolution;
         double theta = angles[cell.getAngleIndex()];
         
         return new HoughPoint(rho, theta);
      }
//      
//      public void pack() {
//         rhoValues = new TreeSet<>(accumulator.rowKeySet());
//         thetaValues = new TreeSet<>(accumulator.columnKeySet());
//      }
      
      /**
       * Represents a column of the two dimensional hough space. The values in this column 
       */
      public static class AngleColumn<T>
      {
         private double angle;
         private int angleIx;
         
         private List<Integer> rhoValues;
         private final Map<Integer, HoughAccumulator<T>> values;
         private Table<Integer, Integer, HoughAccumulator<T>> accumulator;
         
         public AngleColumn(int angleIx, Table<Integer, Integer, HoughAccumulator<T>> accumulator)
         {
            this.angle = angleIx;
            this.accumulator = accumulator;
            this.values = accumulator.column(angleIx);
            this.rhoValues = new ArrayList<Integer>();
            for (Integer i : new TreeSet<>(values.keySet()))
            {
               rhoValues.add(i);
            }
         }
         
         public int size()
         {
            return rhoValues.size();
         }
         
         public HoughAccumulator<T> get(int ix)
         {
            return values.get(Integer.valueOf(ix));
         }
         
         public int indexOf(HoughAccumulator<T> acc)
         {
            if (acc.getAngleIndex() != angleIx)
               throw new IllegalArgumentException("Invalid accumulator. Expected angle of [" + angleIx + "]");
            
            return rhoValues.indexOf(Integer.valueOf(acc.getRhoIndex()));
         }
      }
   }