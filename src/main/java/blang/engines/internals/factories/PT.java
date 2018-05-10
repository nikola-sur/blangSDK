package blang.engines.internals.factories;

import org.eclipse.xtext.xbase.lib.Pair;

import bayonet.distributions.Random;
import blang.engines.ParallelTempering;
import blang.engines.internals.PosteriorInferenceEngine;
import blang.inits.Arg;
import blang.inits.DefaultValue;
import blang.inits.GlobalArg;
import blang.inits.experiments.ExperimentResults;
import blang.inits.experiments.tabwriters.TabularWriter;
import blang.io.BlangTidySerializer;
import blang.runtime.Runner;
import blang.runtime.SampledModel;
import blang.runtime.internals.objectgraph.GraphAnalysis;

public class PT extends ParallelTempering implements PosteriorInferenceEngine  
{
  @GlobalArg ExperimentResults results;
  
  @Arg @DefaultValue("1_000")
  public int nScans = 1_000;
  
  @Arg         @DefaultValue("3")
  public int nPassesPerScan = 3;
  
  @Arg               @DefaultValue("1")
  public Random random = new Random(1);
  
  @Arg                   @DefaultValue("false")
  public boolean printAllTemperatures = false; 
  
  @Override
  public void setSampledModel(SampledModel model) 
  {
    initialize(model, random);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void performInference() 
  {
    BlangTidySerializer tidySerializer = new BlangTidySerializer(results.child(Runner.SAMPLES_FOLDER)); 
    BlangTidySerializer densitySerializer = new BlangTidySerializer(results.child(Runner.SAMPLES_FOLDER)); 
    int sampleIndex = 0;
    for (int iter = 0; iter < nScans; iter++)
    {
      moveKernel(nPassesPerScan);
      for (int pIndex = 0; pIndex < nParticlesPerTemperature; pIndex++)
      {
        if (printAllTemperatures) 
        {
          for (int i = 0; i < temperingParameters.size(); i++)  
          {
            states[i][pIndex].getSampleWriter(tidySerializer).write(Pair.of("sample", sampleIndex), Pair.of("temperature", temperingParameters.get(i)));
            densitySerializer.serialize(states[i][pIndex].logDensity(), "logDensity", Pair.of("sample", iter), Pair.of("temperature", temperingParameters.get(i)));
          }
        }
        else
        {
          getTargetStates()[pIndex].getSampleWriter(tidySerializer).write(Pair.of("sample", sampleIndex));
          densitySerializer.serialize(getTargetStates()[pIndex].logDensity(), "logDensity", Pair.of("sample", iter));
        }
        sampleIndex++;
      }
      swapKernel();
    }
    reportAcceptanceRatios();
  }

  private void reportAcceptanceRatios() 
  {
    TabularWriter tabularWriter = results.child(Runner.MONITORING_FOLDER).getTabularWriter("swapPrs");
    for (int i = 0; i < nChains() - 1; i++)
      tabularWriter.write(Pair.of("chain", i), Pair.of("parameter", temperingParameters.get(i)), Pair.of("pr", swapAcceptPrs[i].getMean()));
  }

  @Override
  public void check(GraphAnalysis analysis) 
  {
    // TODO: may want to check forward simulators ok
    return;
  }
}
