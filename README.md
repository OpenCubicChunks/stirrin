# Stirrin
Applies any interfaces implemented by a class annotated with `@Mixin` to its target class at compile-time


## Configuring the stirrin plugin
```groovy
buildscript {
    repositories {
    }
    dependencies {
        classpath 'io.github.opencubicchunks:stirrin:1.1.2'
    }
}

apply plugin: 'io.github.opencubicchunks.stirrin'

stirrin {
    acceptedJars = ".*minecraft.*"
    configs = [ "mod.mixins.json" ] // list all mixin configs we wish to apply
    debug = false // if true, the artifact transform is always run
}
```

## Example Case
```java
public interface A {
    void foo();
}
```
```java
@Mixin(BlockPos.class)
public class MixinBlockPos implements A { }
```
Using our mixin interface:
```java
BlockPos pos = new BlockPos(0, 0, 0);
// ((A) pos).foo(); // previous usage
pos.foo(); // instead of casting to A here, we can just call the method on BlockPos as it already extends our interface
```
