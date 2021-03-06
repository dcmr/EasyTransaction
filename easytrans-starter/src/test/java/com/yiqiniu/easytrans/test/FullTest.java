package com.yiqiniu.easytrans.test;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Resource;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import com.yiqiniu.easytrans.core.ConsistentGuardian;
import com.yiqiniu.easytrans.core.EasytransConstant;
import com.yiqiniu.easytrans.extensionsuite.impl.database.GetExtentionSuiteDatabase;
import com.yiqiniu.easytrans.log.TransactionLogReader;
import com.yiqiniu.easytrans.log.impl.database.DataBaseTransactionLogConfiguration.DataBaseForLog;
import com.yiqiniu.easytrans.log.vo.LogCollection;
import com.yiqiniu.easytrans.protocol.BusinessIdentifer;
import com.yiqiniu.easytrans.protocol.TransactionId;
import com.yiqiniu.easytrans.protocol.autocps.AutoCpsLocalTransactionExecutor;
import com.yiqiniu.easytrans.rpc.EasyTransRpcConsumer;
import com.yiqiniu.easytrans.test.mockservice.accounting.AccountingService;
import com.yiqiniu.easytrans.test.mockservice.accounting.easytrans.AccountingCpsMethod.AccountingRequest;
import com.yiqiniu.easytrans.test.mockservice.accounting.easytrans.AccountingCpsMethod.AccountingRequestCfg;
import com.yiqiniu.easytrans.test.mockservice.coupon.CouponService;
import com.yiqiniu.easytrans.test.mockservice.coupon.easytrans.UseCouponAutoCpsMethod.UseCouponMethodRequest;
import com.yiqiniu.easytrans.test.mockservice.express.ExpressService;
import com.yiqiniu.easytrans.test.mockservice.express.easytrans.ExpressDeliverAfterTransMethod.ExpressDeliverAfterTransMethodRequest;
import com.yiqiniu.easytrans.test.mockservice.order.NotReliableOrderMessage;
import com.yiqiniu.easytrans.test.mockservice.order.OrderMessage;
import com.yiqiniu.easytrans.test.mockservice.order.OrderService;
import com.yiqiniu.easytrans.test.mockservice.order.OrderService.UtProgramedException;
import com.yiqiniu.easytrans.test.mockservice.point.PointService;
import com.yiqiniu.easytrans.test.mockservice.wallet.WalletService;
import com.yiqiniu.easytrans.test.mockservice.wallet.easytrans.WalletPayTccMethod.WalletPayTccMethodRequest;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { EasyTransTestConfiguration.class },webEnvironment = WebEnvironment.DEFINED_PORT)
public class FullTest {

	@Resource(name = "wholeJdbcTemplate")
	private JdbcTemplate wholeJdbcTemplate;

	@Resource
	OrderService orderService;

	@Resource
	private ConsistentGuardian guardian;

	@Resource
	private TransactionLogReader logReader;

	@Value("${spring.application.name}")
	private String applicationName;

	@Resource
	private EasyTransRpcConsumer consumer;

	@Resource
	private AccountingService accountingService;
	@Resource
	private ExpressService expressService;
	@Resource
	private PointService pointService;
	@Resource
	private WalletService walletService;
	@Autowired(required=false)
	private DataBaseForLog dbForLog;
	@Autowired(required=false)
	private GetExtentionSuiteDatabase suiteDatabase;
	
	@Resource
	private CouponService couponService;

	private ExecutorService executor = Executors.newFixedThreadPool(4);
	// private ExecutorService executor = Executors.newFixedThreadPool(1);

	private Logger LOG = LoggerFactory.getLogger(this.getClass());

	private int concurrentTestId = 100000;
	private boolean enableFescarTest = true;	

	/**
	 * 测试本方法需要先配置好数据库及kafka 在trxtest库中需要创建
	 * executed_trans、idempotent表，建表语句在readme.md中，其余测试业务相关表会自动创建
	 * （基于数据库的事务日志）在translog库中需要创建trans_log_unfinished，trans_log_detail，建表语句也在readme.md中
	 * （基于KAFKA的消息队列）在kafka中创建以下队列（如果KAFKA允许自动创建也可以不创建）,对应的灾备复制策略等请自行考虑调整,框架只保证消息送达KAFKA。（如果kafka没有配置自动创建TOPIC）
	 * trx-test-service_NotReliableOrderMsg
	 * trx-test-service_reconsume_0
	 * trx-test-service_reconsume_1
	 * trx-test-service_reconsume_2
	 * trx-test-service_ReliableOrderMsg
	 * trx-test-service_ReliableOrderMsgCascade
	 * 
	 * 
	 * 测试案例中使用了多个指向同一个数据库的业务数据源模拟分布式事务的场景，这是为了测试方便。
	 * 我们也可以使用多个数据源对应不同的数据库，并启动多个进程进行测试，但需要各位自行调整测试代码
	 * 
	 * 本测试在执行过程中可能会打印出很多异常信息，但同时也有很多异常是UT计划内必须发生的，因此判断ut是否成功，只看最后的assert
	 * 
	 * UT不成功也有可能是由于RPC的超时导致，某个原本预期成功的方法由于调用超时失败了，这在调试的时候尤其常见。
	 * 
	 * */
	@Test
	public void test() {
	    
	    enableFescarTest = true;//fescar的行锁机制经常会导致测试失败（异步commit太慢），因此可以关闭fescar的测试
	    
		try {

			// synchronizer test
			// 清除创建测试初始数据
			cleanAndSetUp();
			
			// 测试主事务成功，从事务也全部成功的场景
			commitedAndSubTransSuccess();
			sleepForFescar();
			
			// 测试在执行COMMIT前发生异常的场景
			rollbackWithExceptionJustBeforeCommit();
	        sleepForFescar();
	          
			// 在主事务中，远程调用执行了一半后发生异常的场景
			rollbackWithExceptionInMiddle();
	        sleepForFescar();

			
			// 在主事务中没有执行过任何远程调用就发生了异常的场景
			rollbackWithExceptionJustAfterStartEasyTrans();

			
			// consistent guardian test
			// 主事务提交了，在跟进最终一致性的时候发生异常的场景
			commitWithExceptionInMiddleOfConsistenGuardian();
	         sleepForFescar();
	         
			// 主事务回滚了，在跟进相关补偿回滚操作时候发生异常的场景
			rollbackWithExceptionInMiddleOfConsistenGuardian();
	        sleepForFescar();
			
			// SAGA sucess test
			sagaSuccessTest();
			sleep(1000);//saga try is async, wait for try finished then execute rollbackWithExceptionInSagaTry
			
			rollbackWithExceptionInSagaTry();
	         sleepForFescar();

			
			// idempotent test
			// 激活线程池
			activateThreadPool();
			// 并发执行TCC操作
			sameMethodConcurrentTcc();
			// 并发执行可补偿操作
			differentMethodConcurrentCompensable();

			//wallet 7000
			//account waste 2000
			//ExpressCount 2
			//point 3000
			//coupon 9998
			// 测试单项功能
			executeTccOnly();//wallet 6000
			executeCompensableOnly();//account waste 3000
			executeAfterTransMethodOnly();//ExpressCount 3
			executeReliableMsgOnly();//point 4000
			executeNotReliableMessageOnly();
			
			// 测试队列消费失败情况
			testMessageQueueConsumeFailue();//point 6000

			// 级联事务成功提交测试
			orderService.buySomethingCascading(1, 1000,enableFescarTest);//wallet 5000,account waste 4000,express count 4, point 7000,coupon 9997
	        sleepForFescar();

			
			// 级联事务回滚测试
			walletService.setExceptionOccurInCascadeTransactionBusinessEnd(true);
			try {
				orderService.buySomethingCascading(1, 1000,enableFescarTest);
			} catch (Exception e) {
			}
			walletService.setExceptionOccurInCascadeTransactionBusinessEnd(false);
			
	        sleepForFescar();

			
			// 级联事务同步成功，且缓存超时测试
			orderService.setCascadeTrxFinishedSleepMills(10000);
			orderService.buySomethingCascading(1, 1000,enableFescarTest);//wallet 4000,account waste 5000,express count 5, point 8000,coupon 9996
			orderService.setCascadeTrxFinishedSleepMills(0);
			
			//测试自动开启事务x
			orderService.buySomethingWithAutoGenId(1, 1000);//wallet 3000,account waste 5000,express count 5, point 8000,coupon 9996

			
		} catch (Exception e) {
		    e.printStackTrace();
			throw(e);
		}


//		// 执行一遍后台补偿任务，以避免上述操作有未补偿成功的
//		// execute consistent guardian in case of timeout
//		List<LogCollection> unfinishedLogs = logReader.getUnfinishedLogs(null, 100, new Date());
//		for (LogCollection logCollection : unfinishedLogs) {
//			guardian.process(logCollection);
//		}
		
		// 等待ConsistentDameon处理在同步补偿中发生异常的事务，配置中已调整为跟进5秒前发生的事务，这里等待30秒是因为要等待UT计划里的消费失败重试次数
		sleep(30000);
		
        // execute consistent guardian in case of timeout
        List<LogCollection> unfinishedLogs = logReader.getUnfinishedLogs(null, 100, new Date());
		Assert.assertTrue("should be clean by daemon",unfinishedLogs.isEmpty());
		
		//其必须等待所有锁都释放后才能正常执行
        executeFescarATOnly();//9995

		//wallet 3000,account waste 5000,express count 5, point 7000,coupon 9995
		Assert.assertTrue(walletService.getUserTotalAmount(1) == 3000);
		Assert.assertTrue(walletService.getUserFreezeAmount(1) == 0);
		Assert.assertTrue(accountingService.getTotalCost(1) == 5000);
		Assert.assertTrue(expressService.getUserExpressCount(1) == 5);
		Assert.assertTrue(pointService.getUserPoint(1) == 8000);
		if(enableFescarTest) {
		    Assert.assertTrue(couponService.getUserCoupon(1) == 9995);
		}
		System.out.println("Test Passed!!");
		
		testFescarLocalLock();
	}

    public void testFescarLocalLock() {
        // test FESCAR local transaction
        enableFescarTest = true;
        sleepForFescar();
        
        UseCouponMethodRequest param = new UseCouponMethodRequest();
        param.setUserId(1);
        param.setCoupon(1l);

        // should be success,it's execute without et
        try {
            AutoCpsLocalTransactionExecutor.executeWithGlobalLockCheck(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    couponService.useCoupon(param);
                    return null;
                }
            });
        } catch (Exception e1) {
            throw new RuntimeException(e1);
        }

        // execute with ET-fescar, it will lock for a while
        boolean fescarExceptionOccur = false;
        executeFescarATOnly();
        try {
            // should be failed
            AutoCpsLocalTransactionExecutor.executeWithGlobalLockCheck(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    couponService.useCoupon(param);
                    return null;
                }
            });
        } catch (Exception e) {
            if(e.getMessage().contains("Obtain Lock failed")) {
                fescarExceptionOccur = true;
            }
        }
        Assert.assertTrue(fescarExceptionOccur);
    }

    private void sleepForFescar() {
        if(enableFescarTest) {
            sleep(1000);//当全局事务未完成时，fescar会锁记录，并抛出异常，因此需要等待其全局事务完成。此为其相对于TCC的一个较大的缺点
        }
    }

	private void sagaSuccessTest() {
		orderService.sagaWalletTest(1, 1000);
	}
	
	public void rollbackWithExceptionInSagaTry() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_IN_SAGA_TRY);
			orderService.sagaWalletTest(1, 1000);
		} catch (Exception e) {
			LOG.info(e.getMessage());
		}
		
		sleep(1000);//等待异步线程异常抛出完毕
		OrderService.clearExceptionSet();
	}

	public void sleep(long sleepTime) {
		try {
			// 等待消息队列两消息送到
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public void testMessageQueueConsumeFailue() {

		// 失败一次后成功
		pointService.setSuccessErrorCount(1);
		executeReliableMsgOnly();

		sleep(2000);
		pointService.setSuccessErrorCount(4);
		executeReliableMsgOnly();

	}

	private void executeNotReliableMessageOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		// OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
		orderService.buySomething(1, 1000,enableFescarTest);
		OrderService.clearNotExecuteSet();
	}

	private void executeAfterTransMethodOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		// OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
	    OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
		orderService.buySomething(1, 1000,enableFescarTest);
		OrderService.clearNotExecuteSet();
	}

	private void executeReliableMsgOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		// OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
	    OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
		orderService.buySomething(1, 1000,enableFescarTest);
		OrderService.clearNotExecuteSet();
	}

	private void executeTccOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		// OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
	    OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
		orderService.buySomething(1, 1000,enableFescarTest);
		OrderService.clearNotExecuteSet();
	}

	private void executeCompensableOnly() {
		// OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
	    OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
		orderService.buySomething(1, 1000,enableFescarTest);
		OrderService.clearNotExecuteSet();
	}
	
	   private void executeFescarATOnly() {
	        OrderService.setNotExecuteBusiness(AccountingRequest.class);
	        OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
	        OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
	        OrderService.setNotExecuteBusiness(OrderMessage.class);
	        OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
//	        OrderService.setNotExecuteBusiness(UseCouponMethodRequest.class);
	        orderService.buySomething(1, 1000,enableFescarTest);
	        OrderService.clearNotExecuteSet();
	    }

	private void differentMethodConcurrentCompensable() {

		final BusinessIdentifer annotation = AccountingRequestCfg.class.getAnnotation(BusinessIdentifer.class);
		final int i = concurrentTestId++;

		final AccountingRequestCfg request = new AccountingRequestCfg();
		request.setAmount(1000l);
		request.setUserId(1);
		TransactionId parentTrxId = new TransactionId(applicationName, "concurrentTest", i);
		HashMap<String, Object> header = new HashMap<>();
		header.put(EasytransConstant.CallHeadKeys.PARENT_TRX_ID_KEY, parentTrxId);
		header.put(EasytransConstant.CallHeadKeys.CALL_SEQ, 1);

		Callable<Object> doCompensableBusinessRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doCompensableBusiness", header,
						request);
			}
		};

		Callable<Object> compensationRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "compensation", header, request);
			}
		};

		List<Callable<Object>> asListTry = Arrays.asList(compensationRequest, doCompensableBusinessRequest,
				compensationRequest, doCompensableBusinessRequest, compensationRequest, doCompensableBusinessRequest,
				compensationRequest, doCompensableBusinessRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListTry);
			for (Future<Object> future : invokeAll) {
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					e.printStackTrace();
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void activateThreadPool() {
		Callable<Object> runnable = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return null;
			}
		};

		try {
			executor.invokeAll(
					Arrays.asList(runnable, runnable, runnable, runnable, runnable, runnable, runnable, runnable));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void sameMethodConcurrentTcc() {
		final BusinessIdentifer annotation = WalletPayTccMethodRequest.class.getAnnotation(BusinessIdentifer.class);
		final int i = concurrentTestId++;

		final WalletPayTccMethodRequest request = new WalletPayTccMethodRequest();
		request.setPayAmount(1000l);
		request.setUserId(1);
		TransactionId parentTrxId = new TransactionId(applicationName, "concurrentTest", i);
		HashMap<String, Object> header = new HashMap<>();
		header.put(EasytransConstant.CallHeadKeys.PARENT_TRX_ID_KEY, parentTrxId);
		header.put(EasytransConstant.CallHeadKeys.CALL_SEQ, 1);

		Callable<Object> tryRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doTry", header, request);
			}
		};

		Callable<Object> cancelRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doCancel", header, request);
			}
		};

		List<Callable<Object>> asListTry = Arrays.asList(tryRequest, tryRequest, tryRequest, tryRequest, tryRequest,
				tryRequest, tryRequest, tryRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListTry);
			for (Future<Object> future : invokeAll) {
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					LOG.info("Concurrent error message:" + e.getMessage());
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		List<Callable<Object>> asListCancel = Arrays.asList(cancelRequest, cancelRequest, cancelRequest, cancelRequest,
				cancelRequest, cancelRequest, cancelRequest, cancelRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListCancel);
			for (Future<Object> future : invokeAll) {
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					LOG.info("Concurrent error message:" + e.getMessage());
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	private void rollbackWithExceptionInMiddleOfConsistenGuardian() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_BEFORE_COMMIT);
			OrderService.setExceptionTag(
					OrderService.EXCEPTION_TAG_IN_MIDDLE_OF_CONSISTENT_GUARDIAN_WITH_ROLLEDBACK_MASTER_TRANS);
			orderService.buySomething(1, 1000,enableFescarTest);
		} catch (Exception e) {
			LOG.info(e.getMessage());
		}
		sleep(1000);//等待异步线程异常抛出执行完毕
		OrderService.clearExceptionSet();
	}

	private void commitWithExceptionInMiddleOfConsistenGuardian() {
		try {
			OrderService.setExceptionTag(
					OrderService.EXCEPTION_TAG_IN_MIDDLE_OF_CONSISTENT_GUARDIAN_WITH_SUCCESS_MASTER_TRANS);
			orderService.buySomething(1, 1000,enableFescarTest);
		} catch (Exception e) {
			LOG.error(e.getMessage());
		}
        sleep(1000);//等待异步线程异常抛出执行完毕
		OrderService.clearExceptionSet();
	}

	private void cleanAndSetUp() {
		wholeJdbcTemplate.batchUpdate(new String[] {
				"Create Table If Not Exists `order` (  `order_id` int(11) NOT NULL AUTO_INCREMENT,  `user_id` int(11) NOT NULL,  `money` bigint(20) NOT NULL,  `create_time` datetime NOT NULL,  PRIMARY KEY (`order_id`)) DEFAULT CHARSET=utf8",
				"TRUNCATE `order`",
				"Create Table If Not Exists `wallet` (  `user_id` int(11) NOT NULL,  `total_amount` bigint(20) NOT NULL,  `freeze_amount` bigint(20) NOT NULL,  PRIMARY KEY (`user_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `wallet`",
				"Create Table If Not Exists  `accounting` (  `accounting_id` int(11) NOT NULL AUTO_INCREMENT,  `p_app_id` varchar(32) NOT NULL,  `p_bus_code` varchar(128) NOT NULL,  `p_trx_id` varchar(64) NOT NULL,  `user_id` int(11) NOT NULL,  `amount` bigint(20) NOT NULL,  `create_time` datetime NOT NULL,  PRIMARY KEY (`accounting_id`)) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8",
				"TRUNCATE `accounting`",
				"Create Table If Not Exists `point` (  `user_id` int(11) NOT NULL,  `point` bigint(20) NOT NULL,  PRIMARY KEY (`user_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `point`",
				"Create Table If Not Exists `express` (  `p_app_id` varchar(32) NOT NULL,  `p_bus_code` varchar(128) NOT NULL,  `p_trx_id` varchar(64) NOT NULL,  `user_id` int(11) NOT NULL,  PRIMARY KEY (`p_app_id`,`p_bus_code`,`p_trx_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `express`",
				"CREATE TABLE if not exists `coupon` ( `user_id` int(11) NOT NULL AUTO_INCREMENT, `coupon` int(11) NOT NULL, PRIMARY KEY (`user_id`)) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8",
				"TRUNCATE `coupon`",
				"TRUNCATE `executed_trans`", "TRUNCATE `idempotent`",
				"INSERT INTO `wallet` (`user_id`, `total_amount`, `freeze_amount`) VALUES ('1', '10000', '0')",
				"INSERT INTO `point` (`user_id`, `point`) VALUES ('1', '0')",
				"INSERT INTO `coupon` (`user_id`, `coupon`) VALUES ('1', '10000');",
				
                "TRUNCATE `fescar_lock`",
                "TRUNCATE `undo_log`"
				});

		if(dbForLog != null || suiteDatabase != null){
		    
		    DataSource dataSource = null;
		    if(dbForLog != null) {
		        dataSource = dbForLog.getDataSource();
		    } else {
		        dataSource = suiteDatabase.getDataSource();
		    }
		    
			JdbcTemplate transLogJdbcTemplate = new JdbcTemplate(dataSource);
			transLogJdbcTemplate
			.batchUpdate(new String[] { "TRUNCATE `trans_log_unfinished`", "TRUNCATE `trans_log_detail`", });
		}

	}

	public void commitedAndSubTransSuccess() {
		orderService.buySomething(1, 1000,enableFescarTest);
	}

	public void rollbackWithExceptionJustBeforeCommit() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_BEFORE_COMMIT);
			orderService.buySomething(1, 1000,enableFescarTest);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}
	


	public void rollbackWithExceptionInMiddle() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_IN_THE_MIDDLE);
			orderService.buySomething(1, 1000,enableFescarTest);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}

	public void rollbackWithExceptionJustAfterStartEasyTrans() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_JUST_AFTER_START_EASY_TRANSACTION);
			orderService.buySomething(1, 1000,enableFescarTest);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}

}
