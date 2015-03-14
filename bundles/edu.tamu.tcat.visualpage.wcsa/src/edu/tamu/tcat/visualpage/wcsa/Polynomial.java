package edu.tamu.tcat.visualpage.wcsa;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import edu.tamu.tcat.visualpage.wcsa.Polynomial.CriticalPoint.Type;

public class Polynomial implements DoubleUnaryOperator
{
   private final double[] coeffs;
   private final int degree;
   
   public static class CriticalPoint
   {
      public static enum Type
      {
         MINIMUM,
         MAXIMUM,
         SADDLE
      }
      
      public CriticalPoint.Type type;
      
      /**
       * x such that f(x) is a critical point 
       */
      public double point;
      
      public CriticalPoint(CriticalPoint.Type type, double x)
      {
         this.type = type;
         point = x;
      }
   }
   
   public Polynomial(double[] coeffs)
   {
      this.coeffs = coeffs;
      this.degree = coeffs.length - 1;
   }
   
   @Override
   public double applyAsDouble(double operand)
   {
      double result = 0;
      for (int i = 0; i <= degree; i++)
      {
         result += coeffs[i] * Math.pow(operand, i);
      }
      
      return result;
   }
   
   public Polynomial differentiate()
   {
      if (degree == 0)
         return new Polynomial(new double[] { 0.0 });
      
      double[] vals = new double[coeffs.length - 1];
      for (int i = 1; i < coeffs.length; i++)
      {
         vals[i - 1] = i * coeffs[i];
      }
      
      return new Polynomial(vals);
   }
   
   public List<Polynomial.CriticalPoint> findCriticalPoints(double a, double b, double step)
   {
      Polynomial diff = differentiate();
      List<Polynomial.CriticalPoint> points = new ArrayList<>();
      double prev = diff.applyAsDouble(a);
      for (double x = a + step; x < b; x += step)
      {
         double y = diff.applyAsDouble(x);
         if (prev < 0 && y > 0) {
            // decreasing -> increasing :: minimum
            points.add(new CriticalPoint(Type.MINIMUM, x));
         } else if (prev > 0 && y < 0) {
            // increasing -> decreasing :: maximum
            points.add(new CriticalPoint(Type.MAXIMUM, x));
         } else {
            // TODO: check for saddle points (i.e. derivative is tangent to x-axis)
         }
         
         prev = y;
      }
      
      return points;
   }
}