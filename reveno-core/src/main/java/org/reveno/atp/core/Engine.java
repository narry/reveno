/** 
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.core;

import org.reveno.atp.api.*;
import org.reveno.atp.api.Configuration.CpuConsumption;
import org.reveno.atp.api.commands.CommandContext;
import org.reveno.atp.api.commands.EmptyResult;
import org.reveno.atp.api.commands.Result;
import org.reveno.atp.api.domain.RepositoryData;
import org.reveno.atp.api.domain.WriteableRepository;
import org.reveno.atp.api.dynamic.AbstractDynamicTransaction;
import org.reveno.atp.api.dynamic.DirectTransactionBuilder;
import org.reveno.atp.api.dynamic.DynamicCommand;
import org.reveno.atp.api.query.QueryManager;
import org.reveno.atp.api.query.ViewsMapper;
import org.reveno.atp.api.transaction.TransactionContext;
import org.reveno.atp.api.transaction.TransactionInterceptor;
import org.reveno.atp.api.transaction.TransactionStage;
import org.reveno.atp.commons.NamedThreadFactory;
import org.reveno.atp.core.api.*;
import org.reveno.atp.core.api.serialization.EventsInfoSerializer;
import org.reveno.atp.core.api.serialization.TransactionInfoSerializer;
import org.reveno.atp.core.api.storage.FoldersStorage;
import org.reveno.atp.core.api.storage.JournalsStorage;
import org.reveno.atp.core.api.storage.SnapshotStorage;
import org.reveno.atp.core.disruptor.DisruptorEventPipeProcessor;
import org.reveno.atp.core.disruptor.DisruptorTransactionPipeProcessor;
import org.reveno.atp.core.disruptor.ProcessorContext;
import org.reveno.atp.core.engine.WorkflowEngine;
import org.reveno.atp.core.engine.components.*;
import org.reveno.atp.core.engine.components.DefaultIdGenerator.NextIdTransaction;
import org.reveno.atp.core.engine.processor.PipeProcessor;
import org.reveno.atp.core.engine.processor.TransactionPipeProcessor;
import org.reveno.atp.core.events.Event;
import org.reveno.atp.core.events.EventHandlersManager;
import org.reveno.atp.core.events.EventPublisher;
import org.reveno.atp.core.impl.EventsCommitInfoImpl;
import org.reveno.atp.core.impl.TransactionCommitInfoImpl;
import org.reveno.atp.core.repository.HashMapRepository;
import org.reveno.atp.core.repository.MutableModelRepository;
import org.reveno.atp.core.repository.SnapshotBasedModelRepository;
import org.reveno.atp.core.restore.DefaultSystemStateRestorer;
import org.reveno.atp.core.serialization.SimpleEventsSerializer;
import org.reveno.atp.core.snapshots.SnapshottersManager;
import org.reveno.atp.core.storage.FileSystemStorage;
import org.reveno.atp.core.views.ViewsDefaultStorage;
import org.reveno.atp.core.views.ViewsManager;
import org.reveno.atp.core.views.ViewsProcessor;
import org.reveno.atp.utils.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class Engine implements Reveno {
	
	public Engine(FoldersStorage foldersStorage, JournalsStorage journalsStorage,
			SnapshotStorage snapshotStorage, ClassLoader classLoader) {
		this.classLoader = classLoader;
		this.foldersStorage = foldersStorage;
		this.journalsStorage = journalsStorage;
		this.snapshotStorage = snapshotStorage;
		this.snapshotsManager = new SnapshottersManager(snapshotStorage, classLoader);
		serializer = new SerializersChain(classLoader);
	}
	
	public Engine(File baseDir) {
		this(baseDir, Engine.class.getClassLoader());
	}
	
	public Engine(File baseDir, ClassLoader classLoader) {
		FileSystemStorage storage = new FileSystemStorage(baseDir, config.revenoJournaling());
		this.classLoader = classLoader;
		this.foldersStorage = storage;
		this.journalsStorage = storage;
		this.snapshotStorage = storage;
		this.snapshotsManager = new SnapshottersManager(snapshotStorage, classLoader);
		serializer = new SerializersChain(classLoader);
	}
	
	public Engine(String baseDir) {
		this(new File(baseDir), Engine.class.getClassLoader());
	}
	
	public Engine(String baseDir, ClassLoader classLoader) {
		this(new File(baseDir), classLoader);
	}

	@Override
	public boolean isStarted() {
		return isStarted;
	}

	@Override
	public void startup() {
		log.info("Engine startup initiated.");
		if (isStarted)
			throw new IllegalStateException("Can't startup engine which is already started.");
		
		init();
		connectSystemHandlers();

		JournalsStorage.JournalStore temp = journalsManager.rollTemp();

		eventPublisher.getPipe().start();
		workflowEngine.init();
		viewsProcessor.process(repository);
		workflowEngine.setLastTransactionId(restorer.restore(repository).getLastTransactionId());

		workflowEngine.getPipe().sync();
		eventPublisher.getPipe().sync();

		journalsManager.rollFrom(temp, workflowEngine.getLastTransactionId());
		
		log.info("Engine is started.");
		isStarted = true;
	}

	@Override
	public void shutdown() {
		log.info("Shutting down engine.");
		isStarted = false;

		workflowEngine.shutdown();
		eventPublisher.getPipe().shutdown();
		
		interceptors.getInterceptors(TransactionStage.JOURNALING).forEach(TransactionInterceptor::destroy);
		interceptors.getInterceptors(TransactionStage.REPLICATION).forEach(TransactionInterceptor::destroy);
		interceptors.getInterceptors(TransactionStage.TRANSACTION).forEach(TransactionInterceptor::destroy);

		journalsManager.destroy();

		eventsManager.close();
		
		snapshotterIntervalExecutor.shutdown();
		
		if (config.revenoSnapshotting().atShutdown()) {
			log.info("Preforming shutdown snapshotting...");
			snapshotAll();
		}
		if (repository instanceof Destroyable) {
			((Destroyable)repository).destroy();
		}
		
		log.info("Engine was stopped.");
	}

	@Override
	public RevenoManager domain() {
		return new RevenoManager() {
			protected RepositorySnapshotter lastSnapshotter;
			
			@Override
			public DirectTransactionBuilder transaction(String name,
					BiConsumer<AbstractDynamicTransaction, TransactionContext> handler) {
				return new DirectTransactionBuilder(name, handler, serializer, transactionsManager,
						commandsManager, classLoader);
			}
			
			@Override
			public <E, V> void viewMapper(Class<E> entityType, Class<V> viewType, ViewsMapper<E, V> mapper) {
				viewsManager.register(entityType, viewType, mapper);
			}
			
			@Override
			public <T> void transactionAction(Class<T> transaction, BiConsumer<T, TransactionContext> handler) {
				serializer.registerTransactionType(transaction);
				transactionsManager.registerTransaction(transaction, handler);
			}
			
			@Override
			public <T> void transactionWithCompensatingAction(Class<T> transaction,
															  BiConsumer<T, TransactionContext> handler,
															  BiConsumer<T, TransactionContext> compensatingAction) {
				serializer.registerTransactionType(transaction);
				transactionsManager.registerTransaction(transaction, handler);
				transactionsManager.registerTransaction(transaction, compensatingAction, true);
			}
			
			@Override
			public RevenoManager snapshotWith(RepositorySnapshotter snapshotter) {
				snapshotsManager.registerSnapshotter(snapshotter);
				lastSnapshotter = snapshotter;
				return this;
			}
			
			@Override
			public void restoreWith(RepositorySnapshotter snapshotter) {
				restoreWith = snapshotter;
			}
			
			@Override
			public void andRestoreWithIt() {
				restoreWith = lastSnapshotter;
			}
			
			@Override
			public void resetSnapshotters() {
				snapshotsManager.resetSnapshotters();
			}
			
			@Override
			public <C> void command(Class<C> commandType, BiConsumer<C, CommandContext> handler) {
				serializer.registerTransactionType(commandType);
				commandsManager.register(commandType, handler);
			}
			
			@Override
			public <C, U> void command(Class<C> commandType, Class<U> resultType, BiFunction<C, CommandContext, U> handler) {
				serializer.registerTransactionType(commandType);
				commandsManager.register(commandType, resultType, handler);
			}

			@Override
			public void serializeWith(List<TransactionInfoSerializer> serializers) {
				serializer = new SerializersChain(serializers);
			}
		};
	}

	@Override
	public QueryManager query() {
		return viewsStorage;
	}

	@Override
	public EventsManager events() {
		return eventsManager;
	}

	@Override
	public ClusterManager cluster() {
		return null;
	}

	@Override
	public Configuration config() {
		return config;
	}

	@Override
	public <R> CompletableFuture<Result<R>> executeCommand(Object command) {
		checkIsStarted();
		
		return workflowEngine.getPipe().execute(command);
	}

	@Override
	public CompletableFuture<EmptyResult> performCommands(List<Object> commands) {
		checkIsStarted();
		
		return workflowEngine.getPipe().process(commands);
	}
	
	@Override
	public <R> CompletableFuture<Result<R>> execute(DynamicCommand command, Map<String, Object> args) {
		try {
			return executeCommand(command.newCommand(args));
		} catch (InstantiationException | IllegalAccessException e) {
			throw new RuntimeException("Failed to execute command.", e);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <R> R executeSync(DynamicCommand command, Map<String, Object> args) {
		try {
			Result<? extends R> r = (Result<? extends R>) execute(command, args).get();
			if (!r.isSuccess()) {
				throw new RuntimeException("Failed to execute command.", r.getException());
			}

			return r.getResult();
		} catch (InterruptedException | ExecutionException e) {
			throw Exceptions.runtime(e);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> R executeSync(Object command) {
		try {
			Result<? extends R> r = (Result<? extends R>) executeCommand(command).get();
			if (!r.isSuccess()) {
				throw new RuntimeException("Failed to execute command.", r.getException());
			}

			return r.getResult();
		} catch (InterruptedException | ExecutionException e) {
			throw Exceptions.runtime(e);
		}
	}

	@Override
	public <R> R executeSync(String command, Map<String, Object> args) {
		Optional<DynamicCommand> dc = getDynamicCommand(command);
		return executeSync(dc.get(), args);
	}

	@Override
	public <R> R executeSync(String command) {
		return executeSync(command, Collections.emptyMap());
	}

	@Override
	public <R> CompletableFuture<Result<R>> execute(String command, Map<String, Object> args) {
		Optional<DynamicCommand> dc = getDynamicCommand(command);
		return execute(dc.get(), args);
	}

	@Override
	public <R> CompletableFuture<Result<R>> execute(String command) {
		return execute(command, Collections.emptyMap());
	}

	public InterceptorCollection interceptors() {
		return interceptors;
	}

	public boolean isClustered() {
		return !failoverManager().isSingleNode();
	}

	protected Optional<DynamicCommand> getDynamicCommand(String command) {
		Optional<DynamicCommand> dc = DirectTransactionBuilder.loadExistedCommand(command, classLoader);
		if (!dc.isPresent()) {
			throw new IllegalArgumentException(String.format("Command %s can't be found! Make sure it was registered" +
					" by domain().transaction(..).command() call!", command));
		}
		return dc;
	}

	protected void checkIsStarted() {
		if (!isStarted)
			throw new IllegalStateException("The Engine must be started first.");
	}
	
	protected WriteableRepository repository() {
		return new HashMapRepository(config.mapCapacity(), config.mapLoadFactor());
	}
	
	protected void init() {
		repository = factory.create(loadLastSnapshot());
		viewsStorage = new ViewsDefaultStorage(config.mapCapacity(), config.mapLoadFactor());
		viewsProcessor = new ViewsProcessor(viewsManager, viewsStorage);
		processor = new DisruptorTransactionPipeProcessor(txBuilder, config.cpuConsumption(), config.revenoDisruptor().bufferSize(), executor);
		eventProcessor = new DisruptorEventPipeProcessor(CpuConsumption.NORMAL, config.revenoDisruptor().bufferSize(), eventExecutor);
		journalsManager = new JournalsManager(journalsStorage, config.revenoJournaling());

		EngineEventsContext eventsContext = new EngineEventsContext().serializer(eventsSerializer)
				.eventsCommitBuilder(eventBuilder).eventsJournaler(journalsManager.getEventsJournaler()).manager(eventsManager);
		eventPublisher = new EventPublisher(eventProcessor, eventsContext);

		workflowContext = new EngineWorkflowContext().serializers(serializer).repository(repository).classLoader(classLoader)
				.viewsProcessor(viewsProcessor).transactionsManager(transactionsManager).commandsManager(commandsManager)
				.eventPublisher(eventPublisher).transactionCommitBuilder(txBuilder).transactionJournaler(journalsManager.getTransactionsJournaler())
				.idGenerator(idGenerator).journalsManager(journalsManager).snapshotsManager(snapshotsManager).interceptorCollection(interceptors)
				.configuration(config).failoverManager(failoverManager());
		workflowEngine = new WorkflowEngine(processor, workflowContext, config.modelType());
		restorer = new DefaultSystemStateRestorer(journalsStorage, workflowContext, eventsContext, workflowEngine);
	}

	protected Optional<RepositoryData> loadLastSnapshot() {
		if (restoreWith != null && restoreWith.hasAny()) {
			return Optional.of(restoreWith.load());
		}
		return Optional.ofNullable(snapshotsManager.getAll().stream()
						.filter(RepositorySnapshotter::hasAny).findFirst()
						.map(RepositorySnapshotter::load).orElse(null));
	}
	
	protected void connectSystemHandlers() {
		domain().transactionAction(NextIdTransaction.class, idGenerator);
		if (config.revenoSnapshotting().every() != -1) {
			TransactionInterceptor nTimeSnapshotter = new SnapshottingInterceptor(config, snapshotsManager, snapshotStorage,
					journalsManager);
			interceptors.add(TransactionStage.TRANSACTION, nTimeSnapshotter);
			interceptors.add(TransactionStage.JOURNALING, nTimeSnapshotter);
		}
	}
	
	protected TxRepository createRepository() {
		switch (config.modelType()) {
		case IMMUTABLE : return new SnapshotBasedModelRepository(repository());
		case MUTABLE : return new MutableModelRepository(repository(), new SerializersChain(classLoader), classLoader);
		}
		return null;
	}

	/**
	 * Since it is very unsecure to obtain Repository data out of transaction workflow,
	 * this method should be used *only* when engine is stopped.
	 */
	protected synchronized void snapshotAll() {
		snapshotsManager.getAll().forEach(s -> {
			RepositorySnapshotter.SnapshotIdentifier id = s.prepare();
			s.snapshot(repository.getData(), id);
			s.commit(id);
		});
	}

	protected FailoverManager failoverManager() {
		return new UnclusteredFailoverManager();
	}

	protected volatile boolean isStarted = false;
	protected TxRepository repository;
	protected SerializersChain serializer;
	protected SystemStateRestorer restorer;
	protected ViewsProcessor viewsProcessor;
	protected WorkflowEngine workflowEngine;
	protected EventPublisher eventPublisher;
	protected TransactionPipeProcessor<ProcessorContext> processor;
	protected PipeProcessor<Event> eventProcessor;
	protected JournalsManager journalsManager;
	protected EngineWorkflowContext workflowContext;
	protected ViewsDefaultStorage viewsStorage;

	protected RepositorySnapshotter restoreWith;

	protected EventsInfoSerializer eventsSerializer = new SimpleEventsSerializer();
	protected TransactionCommitInfo.Builder txBuilder = new TransactionCommitInfoImpl.PojoBuilder();
	protected EventsCommitInfo.Builder eventBuilder = new EventsCommitInfoImpl.PojoBuilder();
	protected EventHandlersManager eventsManager = new EventHandlersManager();
	protected ViewsManager viewsManager = new ViewsManager();
	protected TransactionsManager transactionsManager = new TransactionsManager();
	protected CommandsManager commandsManager = new CommandsManager();
	protected InterceptorCollection interceptors = new InterceptorCollection();
	
	protected DefaultIdGenerator idGenerator = new DefaultIdGenerator();
	
	protected RevenoConfiguration config = new RevenoConfiguration();
	protected ClassLoader classLoader;
	protected JournalsStorage journalsStorage;
	protected FoldersStorage foldersStorage;
	protected SnapshotStorage snapshotStorage;
	protected SnapshottersManager snapshotsManager;
	
	protected final ExecutorService executor = Executors.newFixedThreadPool(7, new NamedThreadFactory("tx"));
	protected final ExecutorService eventExecutor = Executors.newFixedThreadPool(3, new NamedThreadFactory("evn"));
	protected final ScheduledExecutorService snapshotterIntervalExecutor = Executors.newSingleThreadScheduledExecutor();
	protected static final Logger log = LoggerFactory.getLogger(Engine.class);
	
	protected final TxRepositoryFactory factory = (repositoryData) -> {
		final TxRepository repository = createRepository();
		repositoryData.ifPresent(d -> repository.load(d.data));
		return repository;
	};
	
}
