package org.bergman.agentic.openai;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <p>
 * Make a subclass to this class to create a new agent. Add an @description annotation to the class
 * to tell OpenAI how to use the Agent and what its purpose is.
 * </p><p>
 * Add one public method for every tool that should be available to the agent. The requirements on
 * these tool methods are:<br>
 * * They should return an Object<br>
 * * That object should be something that can be interpreted by new GsonBuilder().create().toJson(returnValue)<br>
 * * The method should have a @deprecation annotation telling OpenAI how the tool is used<br>
 * * Every parameter should have a @deprecation annotation telling OpenAI what the parameter is<br>
 * * Accepted parameter types are String, int, long, float, double and boolean
 * </p><p>
 * Once you have an Agent class implemented with all tools, call the static {@link #registerAgent(Class, String)} to register
 * it and the tools with OpenAI. This should only be done once per agent (not per run or even per client, just once
 * globally). If you change the tools later you need to run it again, and then you need to manually
 * go to https://platform.openai.com/assistants and delete the old agent.<br>
 * The return value from {@link #registerAgent(Class, String)} is the assistant id that you use when initializing the Agent object.
 * </p><p>
 * One important thing to note is that for this to work you have to compile your Java project with the -parameters option
 * </p>
 */
public abstract class AbstractAgent {
    private static final String BASE_URL = "https://api.openai.com/v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final HttpClientResponseHandler<String> stringResponseHandler = response -> {
        int status = response.getCode();
        if (status >= 200 && status < 300) {
            return new String(response.getEntity().getContent().readAllBytes());
        } else {
            throw new IOException("Unexpected response status: " + status);
        }
    };
    
    private final CloseableHttpClient client = HttpClients.createDefault();

    private final String apiKey;
    private final String assistantId;
    private final long timeoutMs;
    private final boolean debug;
    
    /**
     * The AgentThread represents one session against the agent. It should not be confused with Java threads and is not
     * related to such threads in any way. You create a thread with {@link AbstractAgent#createThread()} and then you
     * send a prompt and wait for a reply, and then you can send a new prompt as a follow up, until you close it
     * (which will cause the thread to be deleted from the OpenAI server).
     */
    public class AgentThread implements AutoCloseable {
    	private final String threadId;
    	
    	private AgentThread(String threadId) {
    		this.threadId = threadId;
    	}
    	
    	/**
    	 * Send a prompt (e.g. a user question or follow up to previous question) to the agent and wait for a reply.
    	 * This will possibly cause Tools of your agent implementation to be called.
    	 * 
    	 * @param prompt The prompt to send to the agent (user question or follow up to earlier question)
    	 * @return The reply from the agent
    	 * @throws AgentException If anything went wrong in the agent communication
    	 */
    	public String promptAgent(String prompt) throws AgentException {
    		return AbstractAgent.this.promptAgent(threadId, prompt);
    	}

    	/**
    	 * Will cause the thread to be deleted on the server. It is not necessary to delete OpenAI threads and they
    	 * can normally be useful to have around to be able to resume them, but for our agent we currently don't have
    	 * a way to resume a thread and therefore we will clean it up instead.
    	 */
		@Override
		public void close() throws Exception {
			destroyThread(threadId);
		}
    }
    
    /**
     * There currently isn't more granular error handling than a single exception class in our agentic framework.
     * Often there is a cause that may provide more information.
     */
    public static class AgentException extends Exception {
    	private static final long serialVersionUID = 1L;

    	public AgentException(Throwable cause) {
    		super("Failed to prompt agent", cause);
    	}

    	public AgentException(String message) {
    		super(message);
    	}

    	public AgentException(String message, Throwable cause) {
    		super(message, cause);
    	}
    }
    
    /**
     * Create a new Agent implementation.
     * 
     * @param apiKey The OpenAI API key to use (Created on https://platform.openai.com/)
     * @param assistantId The assistant id (which is returned by {@link AbstractAgent#registerAgent(Class, String)})
     * @param timeoutMs Timeout for a single prompt call
     * @param debug true to get debug output, e.g. what tools the agent calls
     */
    protected AbstractAgent(String apiKey, String assistantId, long timeoutMs, boolean debug) {
    	this.apiKey = apiKey;
    	this.assistantId = assistantId;
    	this.timeoutMs = timeoutMs;
    	this.debug = debug;
    }
    
    /**
     * Call this to register the agent. This should only be done once for an agent, unless the Tool signatures change
     * (in which case you need to rerun it, change the assistant id in your project, and manually delete the old agent
     * in https://platform.openai.com/assistants)
     * 
     * @param agentClass The class of your AbstractAgent subclass to register
     * @param apiKey The OpenAI API key to use (Created on https://platform.openai.com/)
     * @return The assistant ID that you can hard code in your Agent implementation when calling the base class constructor
     * @throws AgentException If it fails to register the agent
     */
    public static String registerAgent(Class<? extends AbstractAgent> agentClass, String apiKey) throws AgentException {
    	Class<?> abstractAgent = AbstractAgent.class;
        if (!abstractAgent.isAssignableFrom(agentClass) || agentClass == abstractAgent) {
            throw new AgentException("The registered agent must be a subclass of " + abstractAgent.getName());
        }
        description classDesc = agentClass.getAnnotation(description.class);
        if (classDesc == null) {
        	throw new AgentException("Agent class must have @description");
        }

        JsonArray toolsArray = new JsonArray();

        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(description.class) &&
            	method.getReturnType() != void.class) {
                JsonObject function = new JsonObject();
                function.addProperty("name", method.getName());
                function.addProperty("description", method.getAnnotation(description.class).value());

                JsonObject parameters = new JsonObject();
                parameters.addProperty("type", "object");

                JsonObject properties = new JsonObject();
                List<String> required = new ArrayList<>();

                Parameter[] params = method.getParameters();
                for (Parameter param : params) {
                    description paramDesc = param.getAnnotation(description.class);
                    if (paramDesc == null) {
                    	continue;
                    }

                    JsonObject paramDef = new JsonObject();
                    paramDef.addProperty("type", mapType(param.getType()));
                    paramDef.addProperty("description", paramDesc.value());
                    properties.add(param.getName(), paramDef);
                    required.add(param.getName());
                }

                parameters.add("properties", properties);
                parameters.add("required", GSON.toJsonTree(required));
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                function.add("parameters", parameters);
                toolsArray.add(tool);
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("name", agentClass.getSimpleName());
        payload.addProperty("instructions", classDesc.value());
        payload.addProperty("model", "gpt-4-1106-preview");
        payload.add("tools", toolsArray);

        HttpPost post = new HttpPost(BASE_URL + "/assistants");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("OpenAI-Beta", "assistants=v2");
        post.setEntity(new StringEntity(GSON.toJson(payload), ContentType.APPLICATION_JSON));

        String result;
        try {
	        CloseableHttpClient client = HttpClients.createDefault();
	        result = client.execute(post, response -> {
	            String body = new String(response.getEntity().getContent().readAllBytes());
	            if (response.getCode() >= 200 && response.getCode() < 300) {
	                return JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();
	            } else {
	                throw new IOException("Failed to register assistant: " + body);
	            }
	        });
	        client.close();
        }
        catch (Exception e) {
        	throw new AgentException("Failed to register assistant", e);
        }
        return result;
    }

    /**
     * Create a new thread that can be used for communicating with the agent.
     * See AgentThread.
     * @return The thread that can be used for prompting the agent (should be closed when done)
     * @throws AgentException If it fails to create the thread
     */
    public AgentThread createThread() throws AgentException {
    	try {
	        HttpPost post = new HttpPost(BASE_URL + "/threads");
	        populateHeader(post, false);
	
	        String json = client.execute(post, stringResponseHandler);
	        return new AgentThread(JsonParser.parseString(json).getAsJsonObject().get("id").getAsString());
    	}
    	catch (Throwable t) {
    		throw new AgentException(t);
    	}
    }

    private String promptAgent(String threadId, String prompt) throws AgentException {
    	try {
	        postUserMessage(threadId, prompt);
	        String runId = runAssistant(threadId);
	        
	        long startTime = System.currentTimeMillis();
	
	        JsonObject runStatus;
	        do {
	        	if (System.currentTimeMillis() - startTime > timeoutMs) {
	                throw new TimeoutException("Timed out waiting for run to complete");
	            }
	        	
	            Thread.sleep(1500);
	            runStatus = getRunStatus(threadId, runId);
	
		        if ("requires_action".equals(runStatus.get("status").getAsString())) {
		        	JsonArray toolCalls = runStatus.getAsJsonObject("required_action")
		                    					   .getAsJsonObject("submit_tool_outputs")
		                                           .getAsJsonArray("tool_calls");
		
		        	JsonArray toolOutputs = new JsonArray();
	
		        	for (JsonElement toolCallElem : toolCalls) {
		        	    JsonObject toolCall = toolCallElem.getAsJsonObject();
		        	    String functionName = toolCall.getAsJsonObject("function").get("name").getAsString();
		        	    JsonObject arguments = JsonParser
		        	        .parseString(toolCall.getAsJsonObject("function").get("arguments").getAsString())
		        	        .getAsJsonObject();
		        	    
		        	    if (debug) {
		        	    	System.out.println(" ... calling tool: " + 
		        	    					   functionName + " - " + 
		        	    					   toolCall.getAsJsonObject("function").get("arguments").getAsString()
		        	    					   .replace("\n", "").replace("\r", ""));
		        	    }

		        	    // This will be overwritten if a matching tool was found
		        	    Object toolResult = createUnknownToolReply(functionName);
		        	    
		        	    // Use a reflection to find a method in the subclass that matches the tool name
		        	    // and that has the description annotation (which indicates that it was reported as a tool)
		        	    Method[] methods = this.getClass().getDeclaredMethods();
		        	    for (Method method : methods) {
		        	    	try {
			        	        if (method.getName().equals(functionName) &&
			        	        	method.isAnnotationPresent(description.class) &&
			        	        	method.getReturnType() != void.class) {
			        	            Object[] params = Arrays.stream(method.getParameters())
			        	                .map(param -> GSON.fromJson(arguments.get(param.getName()), param.getType()))
			        	                .toArray();
			        	            method.setAccessible(true);
			        	            toolResult = method.invoke(this, params);
			        	            break;
			        	        }
		        	    	}
		        	    	catch (Throwable t) {
		        	    		t.printStackTrace();
		        	    	}
		        	    }
	
		        	    JsonObject output = new JsonObject();
		        	    output.addProperty("tool_call_id", toolCall.get("id").getAsString());
		        	    output.addProperty("output", GSON.toJson(toolResult)); 
	
		        	    toolOutputs.add(output);
		        	}
	
		        	submitToolOutputs(threadId, runId, toolOutputs);
		        }
	        } while (!"completed".equals(runStatus.get("status").getAsString()));
	
	        return getLastAssistantMessage(threadId);
    	}
    	catch (Throwable t) {
    		throw new AgentException(t);
    	}
    }
    
    private JsonObject createUnknownToolReply(String functionName) {
    	JsonObject error = new JsonObject();
    	error.addProperty("error", "Unknown tool: " + functionName);
    	return error;
    }
    
    private static String mapType(Class<?> clazz) {
        if (clazz == String.class) return "string";
        if (clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class) return "integer";
        if (clazz == float.class || clazz == Float.class || clazz == double.class || clazz == Double.class) return "number";
        if (clazz == boolean.class || clazz == Boolean.class) return "boolean";
        return "string";  // fallback
    }

    private void destroyThread(String threadId) throws IOException {
    	HttpDelete delete = new HttpDelete(BASE_URL + "/threads/" + threadId);
        populateHeader(delete, false);

        client.execute(delete, response -> {
            int status = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes());

            if (status == 200) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("deleted") && json.get("deleted").getAsBoolean()) {
                	if (debug) {
                		System.out.println("Thread deleted: " + json.get("id").getAsString());
                	}
                    return null;
                } else {
                    throw new IOException("Delete response was 200 but not confirmed as deleted.");
                }
            } else {
                throw new IOException("Failed to delete thread: HTTP " + status + " - " + body);
            }
        });
    }
    
    private void populateHeader(HttpUriRequestBase request, boolean includeContentType) {
    	request.setHeader("Authorization", "Bearer " + apiKey);
    	request.setHeader("OpenAI-Beta", "assistants=v2");
    	if (includeContentType) {
    		request.setHeader("Content-Type", "application/json");
    	}
    }

    private void postUserMessage(String threadId, String content) throws IOException {
        HttpPost post = new HttpPost(BASE_URL + "/threads/" + threadId + "/messages");
        populateHeader(post, true);

        JsonObject payload = new JsonObject();
        payload.addProperty("role", "user");
        payload.addProperty("content", content);

        post.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        client.execute(post, stringResponseHandler);
    }

    private String runAssistant(String threadId) throws IOException {
        HttpPost post = new HttpPost(BASE_URL + "/threads/" + threadId + "/runs");
        populateHeader(post, true);

        JsonObject payload = new JsonObject();
        payload.addProperty("assistant_id", assistantId);

        post.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

        String json = client.execute(post, stringResponseHandler);
        return JsonParser.parseString(json).getAsJsonObject().get("id").getAsString();
    }

    private JsonObject getRunStatus(String threadId, String runId) throws IOException {
        HttpGet get = new HttpGet(BASE_URL + "/threads/" + threadId + "/runs/" + runId);
        populateHeader(get, false);

        String json = client.execute(get, stringResponseHandler);
        return JsonParser.parseString(json).getAsJsonObject();
    }
    
    private void submitToolOutputs(String threadId, String runId, JsonArray toolOutputs) throws IOException {
        HttpPost post = new HttpPost(BASE_URL + "/threads/" + threadId + "/runs/" + runId + "/submit_tool_outputs");
        populateHeader(post, true);

        JsonObject payload = new JsonObject();
        payload.add("tool_outputs", toolOutputs);
        
        post.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

        client.execute(post, stringResponseHandler);
    }
    
    private String getLastAssistantMessage(String threadId) throws IOException {
        HttpGet get = new HttpGet(BASE_URL + "/threads/" + threadId + "/messages");
        populateHeader(get, false);

        String json = client.execute(get, stringResponseHandler);
        JsonArray messages = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data");

        for (JsonElement msgElem : messages) {
            JsonObject msg = msgElem.getAsJsonObject();
            if ("assistant".equals(msg.get("role").getAsString())) {
                JsonArray contentArr = msg.getAsJsonArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    JsonObject contentObj = contentArr.get(0).getAsJsonObject();
                    if (contentObj.has("text")) {
                        return contentObj.getAsJsonObject("text").get("value").getAsString();
                    }
                }
            }
        }
        return "[No assistant reply found]";
    }
}
