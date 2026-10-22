package io.boomerang.misc;

import org.junit.jupiter.api.Test;
import com.openpojo.reflection.filters.FilterPackageInfo;
import com.openpojo.validation.Validator;
import com.openpojo.validation.ValidatorBuilder;
import com.openpojo.validation.rule.impl.GetterMustExistRule;
import com.openpojo.validation.rule.impl.SetterMustExistRule;
import com.openpojo.validation.test.impl.GetterTester;
import com.openpojo.validation.test.impl.SetterTester;

public class ModelTests {

  private static final String[] packages =
      {"io.boomerang.model", "io.boomerang.mongo.entity", "io.boomerang.model.profile"};

  @Test
  public void verifyExternalServiceModels() {

    for (String packge : packages) {
      Validator validator = ValidatorBuilder.create()

          .with(new GetterMustExistRule()).with(new SetterMustExistRule()).with(new SetterTester())
          .with(new GetterTester()).build();

      validator.validate(packge, new FilterPackageInfo());
    }

  }
}
