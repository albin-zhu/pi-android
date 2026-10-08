package dev.zfold.assistant;
public final class SpringMathCheck {
  static void near(double a,double b){if(Math.abs(a-b)>1e-6)throw new AssertionError(a+" != "+b);}
  public static void main(String[] args){
    double[] once=SpringMath.step(12,20,0,.032),half=SpringMath.step(12,20,0,.016),twice=SpringMath.step(half[0],half[1],0,.016);near(once[0],twice[0]);near(once[1],twice[1]);
    double x=12,v=0;for(int i=0;i<180;i++){double[] s=SpringMath.step(x,v,0,1.0/60);x=s[0];v=s[1];if(x<0)throw new AssertionError("unexpected overshoot");}near(x,0);
    double[] retarget=SpringMath.step(4,90,-3,0);near(retarget[0],4);near(retarget[1],90);
    if(SpringMath.project(1000)<=0||SpringMath.project(-1000)>=0)throw new AssertionError("projection direction");
    System.out.println("spring integration, retarget continuity and projection checks passed");
  }
}
