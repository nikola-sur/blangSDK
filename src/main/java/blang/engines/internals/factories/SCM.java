package blang.engines.internals.factories;

import org.eclipse.xtext.xbase.lib.Pair;

import bayonet.distributions.Random;
import bayonet.math.NumericalUtils;
import bayonet.smc.ParticlePopulation;
import blang.engines.AdaptiveJarzynski;
import blang.engines.internals.LogSumAccumulator;
import blang.engines.internals.PosteriorInferenceEngine;
import blang.inits.Arg;
import blang.inits.DefaultValue;
import blang.inits.GlobalArg;
import blang.inits.experiments.ExperimentResults;
import blang.inits.experiments.tabwriters.TidySerializer;
import blang.io.BlangTidySerializer;
import blang.runtime.Runner;
import blang.runtime.SampledModel;
import blang.runtime.internals.objectgraph.GraphAnalysis;
import briefj.BriefParallel;

import blang.System;

/**
 * Sequential Change of Measure implementation.
 */
public class SCM extends AdaptiveJarzynski implements PosteriorInferenceEngine
{
  @GlobalArg public ExperimentResults results = new ExperimentResults();
  
  @Arg(description = "Number of rejuvenation passes to do after the change of measure.")     
                    @DefaultValue("5")
  public int nFinalRejuvenations = 5;

  @Arg                             @DefaultValue("true")
  public boolean approximateChiSquareDivergence = true;
  
  SampledModel model;
  
  @Override
  public void setSampledModel(SampledModel model) 
  { 
    this.model = model;
  }

  private void checkValidArguments() {
    if (nFinalRejuvenations < 0) {
      throw new RuntimeException("nFinalRejuvenation must be non-negative.");
    }
    if (nFinalRejuvenations == 0) {
      throw new UnsupportedOperationException("Zero-valued nFinalRejuvenation is currently unsupported.");
    }
    if (nFinalRejuvenations == 0 && approximateChiSquareDivergence) {
      throw new UnsupportedOperationException("Chi-square approximation without final rejuvenation is currently unsupported.");
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void performInference() 
  {
    checkValidArguments();

    // create approx
    ParticlePopulation<SampledModel> approximation = getApproximation(model);
    
    // write Z estimate
    double logNormEstimate = approximation.logNormEstimate();
    System.out.println("Log normalization constant estimate: " + logNormEstimate);
    results.getTabularWriter(Runner.LOG_NORMALIZATION_ESTIMATE).write(
        Pair.of(Runner.LOG_NORMALIZATION_ESTIMATOR, "SCM"),
        Pair.of(TidySerializer.VALUE, logNormEstimate)
      );
    
    // resample & rejuvenate the last iteration to simplify processing downstream
    if (!isUniform(approximation)) // could happen if there were zero-weight particles in last round
      approximation = approximation.resample(random, resamplingScheme);
    rejuvenate(parallelRandomStreams, approximation);
    
    // write Chi-square estimate
    if (approximateChiSquareDivergence) {
      double logChiSqrDiv = approximateChiSquareDivergenceInPlace(approximation);
      System.out.println("Log χ2 divergence estimate: " + logChiSqrDiv);
      results.getTabularWriter(Runner.LOG_CHI_SQUARE_DIVERGENCE_ESTIMATE).write(
          Pair.of(Runner.LOG_NORMALIZATION_ESTIMATOR, "SCM"),
          Pair.of(TidySerializer.VALUE, logChiSqrDiv)
      );
    }

    // write samples
    BlangTidySerializer tidySerializer = new BlangTidySerializer(results.child(Runner.SAMPLES_FOLDER)); 
    BlangTidySerializer densitySerializer = new BlangTidySerializer(results.child(Runner.SAMPLES_FOLDER)); 
    int particleIndex = 0;
    for (SampledModel model : approximation.particles)  
    {
      model.getSampleWriter(tidySerializer).write(Pair.of(Runner.sampleColumn, particleIndex)); 
      densitySerializer.serialize(model.logDensity(), "logDensity", Pair.of(Runner.sampleColumn, particleIndex));
      particleIndex++;
    }



  }
  
  private double approximateChiSquareDivergenceHelper(ParticlePopulation<SampledModel> pop, boolean isPosterior) {

    double logNormEst = isPosterior ? pop.logNormEstimate() : 0;
    int n = pop.nParticles();
    double [] logTerms = new double[n];
    LogSumAccumulator logSumAccumulator = new LogSumAccumulator();

    BriefParallel.process(nParticles, nThreads.numberAvailable(), particleIndex ->
    {
      double logPosteriorDensity = pop.particles.get(particleIndex).logDensity(1) - logNormEst;
      double logPriorDensity = pop.particles.get(particleIndex).logDensity(0);
      double logTotalDensity = NumericalUtils.logAdd(logPosteriorDensity, logPriorDensity);
      logTerms[particleIndex] = 2 * logPosteriorDensity - logPriorDensity - logTotalDensity;
    });

    for (double logTerm : logTerms)
      logSumAccumulator.add(logTerm);

    return logSumAccumulator.logSum() - Math.log(n);
  }

  private double approximateChiSquareDivergenceInPlace(ParticlePopulation<SampledModel> posteriorPopulation) {
    // Multiple mixture estimator (Section 5 of Owen and Zhou 2000)
    // https://www.jstor.org/stable/2669533
    double logTermPosterior = approximateChiSquareDivergenceHelper(posteriorPopulation, true);
    BriefParallel.process(nParticles, nThreads.numberAvailable(), particleIndex ->
    {
      posteriorPopulation.particles.get(particleIndex).setExponent(0);
      posteriorPopulation.particles.get(particleIndex).forwardSample(random, false);
    });
    double logTermPrior = approximateChiSquareDivergenceHelper(posteriorPopulation, false);

    return NumericalUtils.logAdd(logTermPosterior, logTermPrior);
  }

  public static boolean isUniform(ParticlePopulation<?> pop)
  {
    for (int i = 0; i < pop.nParticles(); i++) 
      if (pop.getNormalizedWeight(i) != 1.0 / ((double) pop.nParticles()))
        return false;
    return true;
  }
  
  private void rejuvenate(Random [] randoms, final ParticlePopulation<SampledModel> finalPopulation)
  {
    if (nFinalRejuvenations == 0) 
      return;
    System.out.println("Final rejuvenation started");
    deepCopyParticles(finalPopulation);
    BriefParallel.process(nParticles, nThreads.numberAvailable(), particleIndex ->
    {
      Random random = randoms[particleIndex];
      for (int i = 0; i < nFinalRejuvenations; i++)
        finalPopulation.particles.get(particleIndex).posteriorSamplingScan(random);
    });
  }

  @Override
  public void check(GraphAnalysis analysis) 
  {
    // TODO: may want to check forward simulators ok
    return;
  }
  
  public static final String
  
    propagationFileName = "propagation",
    resamplingFileName = "resampling",
    
    essColumn = "ess",
    logNormalizationColumn = "logNormalization",
    iterationColumn = "iteration",
    annealingParameterColumn = "annealingParameter";

  @Override
  protected void recordPropagationStatistics(int iteration, double temperature, double ess) {
    results.child(Runner.MONITORING_FOLDER).getTabularWriter(propagationFileName).write(
        Pair.of(iterationColumn, iteration),
        Pair.of(annealingParameterColumn, temperature),
        Pair.of(essColumn, ess)
    );
    super.recordPropagationStatistics(iteration, temperature, ess);
  }

  @Override
  protected void recordResamplingStatistics(int iteration, double temperature, double logNormalization) {
    results.child(Runner.MONITORING_FOLDER).getTabularWriter(resamplingFileName).write(
        Pair.of(iterationColumn, iteration),
        Pair.of(annealingParameterColumn, temperature),
        Pair.of(logNormalizationColumn, logNormalization)
    );
    super.recordResamplingStatistics(iteration, temperature, logNormalization);
  }
}
