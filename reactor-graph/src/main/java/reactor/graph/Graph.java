package reactor.graph;

import reactor.core.Environment;
import reactor.core.Reactor;
import reactor.core.alloc.factory.BatchFactorySupplier;
import reactor.core.spec.Reactors;
import reactor.event.Event;
import reactor.event.dispatch.Dispatcher;
import reactor.function.Consumer;
import reactor.function.Supplier;
import reactor.util.Assert;
import reactor.util.UUIDUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Jon Brisbin
 */
public class Graph<T> implements Consumer<T> {

	private final Map<String, Node<T>> nodes = new ConcurrentHashMap<String, Node<T>>();

	private final Environment                 env;
	private final Dispatcher                  dispatcher;
	private final BatchFactorySupplier<Event> eventFactory;
	private       Node<T>                     startNode;

	private Graph(Environment env, Dispatcher dispatcher) {
		this.env = env;
		this.dispatcher = dispatcher;
		this.eventFactory = new BatchFactorySupplier<Event>(
				1024,
				new Supplier<Event>() {
					@SuppressWarnings("unchecked")
					@Override
					public Event get() {
						return new Event(null);
					}
				}
		);
	}

	public static <T> Graph<T> create(Environment env) {
		return new Graph<T>(env, env.getDefaultDispatcher());
	}

	public static <T> Graph<T> create(Environment env, String dispatcher) {
		return new Graph<T>(env, env.getDispatcher(dispatcher));
	}

	public Graph<T> startIn(String name) {
		Node<T> node = getNode(name);
		this.startNode = node;
		return this;
	}

	public Node<T> node() {
		return node(UUIDUtils.create().toString());
	}

	public Node<T> node(String name) {
		Assert.isTrue(!nodes.containsKey(name), "A Node is already created with name '" + name + "'");
		Reactor reactor = Reactors.reactor(env, dispatcher);
		Node<T> node = new Node<T>(name, this, reactor);
		nodes.put(name, node);
		return node;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void accept(T t) {
		if(null == startNode && nodes.size() == 1) {
			startNode = nodes.values().iterator().next();
		}
		Assert.notNull(startNode, "No initial starting Node specified. Call Graph.startIn(String) to set one.");
		startNode.notifyValue(eventFactory.get().setData(t));
	}

	BatchFactorySupplier<Event> getEventFactory() {
		return eventFactory;
	}

	Node<T> getNode(String name) {
		Assert.isTrue(nodes.containsKey(name), "No Node named '" + name + "' found.");
		return nodes.get(name);
	}

	@Override
	public String toString() {
		return "Graph{" +
				"nodes=" + nodes +
				", startNode=" + startNode +
				'}';
	}
}
