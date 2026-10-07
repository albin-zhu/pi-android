package dev.zfold.assistant;
/** Pure analytical critical spring and exponential momentum projection. */
final class SpringMath {
  static double[] step(double value,double velocity,double target,double seconds){
    double omega=22,offset=value-target,b=velocity+omega*offset,e=Math.exp(-omega*Math.max(0,seconds));
    return new double[]{target+(offset+b*seconds)*e,(velocity-omega*b*seconds)*e};
  }
  static float project(float velocity){return (velocity/1000f)*.998f/(1-.998f);}
}
