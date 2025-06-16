package org.bergman.agentic.demo;

import java.util.Scanner;

import org.bergman.agentic.openai.description;
import org.bergman.agentic.openai.AbstractAgent;

@description(
		"""
		You are assisting a development team with questions on their specific development environment.
		For this you have a graph which is an export from Stack Overflow for teams. It has posts and comments on those posts.
		The original post is usually a question, and the other posts are answers on that question.
		All posts and comments has a link to the user that posted them.
		""")
public class SOChatAgent extends AbstractAgent implements OpenAIConnection {
	private static final String ASSISTANT_ID = "asst_********************";
	private static final long TIMEOUT_MS = 60000;
	private static final boolean DEBUG = true;

	protected SOChatAgent() {
		super(API_KEY, ASSISTANT_ID, TIMEOUT_MS, DEBUG);
	}
	
	@description(
			"""
			Find relevant questions (topics) in the graph based on vector search on the question the user asked (the prompt)
			""")
	public Object findRelevantQuestions(
			@description("The question as asked by the user") String userQuestion
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getRelevantQuestions(userQuestion);
		}
	}

	@description(
			"""
			For a specific question (topic), get all posts in that thread (the question itself and all answers).
			The result is unsorted, but there is a created field with when it was posted.
			""")
	public Object retrieveThread(
			@description("The id of the question/topic to get the thread for") String questionId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getThread(questionId);
		}
	}

	@description(
			"""
			For a specific question (topic), get the answer that has been indicated as the accepted answer
			(if there is one, otherwise it returns a string that says 'No accepted answer')
			""")
	public Object retrieveAcceptedAnswer(
			@description("The id of the question/topic to get the accepted answer for") String questionId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			var result = neo4j.getAcceptedAnswer(questionId);
			if (result == null) {
				return "No accepted answer";
			}
			return result;
		}
	}

	@description(
			"""
			Fetch all comments for a specific post (question or answer).
			This may be an empty list if there are no comments.
			""")
	public Object retrieveComments(
			@description("The id of the post to get the comments for") String postId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getComments(postId);
		}
	}

	@description(
			"""
			Get the user that posted a question, an answer or a comment.
			""")
	public Object getUser(
			@description("The id of the post (question or answer) or comment for which to get the user who posted.") String entityId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getUser(entityId);
		}
	}

	@description(
			"""
			Get all posts (questions and answers) posted by a specific user.
			""")
	public Object getUserPosts(
			@description("The user id to get the posted posts for.") String userId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getUserPosts(userId);
		}
	}

	@description(
			"""
			Get all comments posted by a specific user.
			""")
	public Object getUserComments(
			@description("The user id to get the posted comments for.") String userId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			return neo4j.getUserComments(userId);
		}
	}

	@description(
			"""
			Get the post that an answer or a comment was posted on.
			If there is no parent (i.e. the post was a question) it return the string 'No parent'
			""")
	public Object getParentPost(
			@description("The id of the post (answer) or comment") String entityId
			) throws Exception {
		try(Neo4jConnection neo4j = new Neo4jConnection()) {
			var result = neo4j.getParentPost(entityId);
			if (result == null) {
				return "No parent";
			}
			return result;
		}
	}
	
	public static void main(String[] args) throws Exception {
		SOChatAgent agent = new SOChatAgent();
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
	}

	// The below should only be used once to register the agent
	// (and again if tools change, but then you need to manually delete the old agent)
	/*public static void main(String[] args) throws Exception {
		System.out.println(registerAgent(SOChatAgent.class, API_KEY));
	}*/
}
