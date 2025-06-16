# agentic-java
A java framework to write AI Agents (currently only for OpenAI)

The framework (only one abstract class and one annotation) is in src/main/java/org/bergman/agentic/openai/.
To create your own agent, just inherit this abstract class. Create the methods you want to have as tools for
the agent (no special demands on the tool methods except that they should take simple primitives or strings
as parameters and return something that can be parsed as JSON with GSON).
The class, the tool methods and all parameters for the tools methods must have a @description annotation
that tells the agent what it should do and how the tools should be used.

```
@description("You are a calculator")
public class MyAgent extends AbstractAgent {
  public MyAgent() {
    super("sk-proj-.......", "asst_.......", 60000, false);
  }
  
  @description("Sum two integer numbers")
  public int sum(@description("First value")int a, @description("Second value")int b) {
    return a + b;
  }
  
  @description("Multiply two integer numbers")
  public int mult(@description("First value")int a, @description("Second value")int b) {
    return a * b;
  }
}
```

Once your agent subclass is implemented, with all the necessary @description annotations, do a single
call to the static registerAgent() method to register it (this should only be called once, not each time
your application starts). The return value of that is the assistant id to use as the first parameter in the
call to the constructor.

```
System.out.println(AbstractAgent.registerAgent(MyAgent.class, "sk-proj-......."));
```

To use the agent you create an agent thread object that is used for prompting the agent. You can continue
a conversation until the agent thread is closed (which causes the thread to be deleted on the server).

```
MyAgent agent = new MyAgent();
try(Scanner scanner = new Scanner(System.in);
    AbstractAgent.AgentThread thread = agent.createThread()) {
  while (true) {
    String input = scanner.nextLine();
    if (input.trim().equalsIgnoreCase("exit")) {
      break;
    }
    System.out.println(thread.promptAgent(input));
  }
}
```

There is a demo agent using Neo4j in src/main/java/org/bergman/agentic/demo/

Note that for this to work, your java project must be compiled with the -parameters option (for the method
parameters to be available in refactoring).
