import React, { useCallback, useState } from 'react';
import { Button, Message, Modal, Typography } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import IlinkQrLogin from '@renderer/components/ilink/IlinkQrLogin';
import { useIlinkSession } from '@renderer/services/ilinkSession';

const { Paragraph, Text, Title } = Typography;

/**
 * iLink 账号设置页：与登录页共用同一份登录态（useIlinkSession），所以这边扫码登录
 * 后，登录页那边立刻也变成已登录，反之亦然。
 *
 * 页面上只做三件事：看登录态、试推送、退出登录。二维码与轮询都在 IlinkQrLogin 里。
 */
export default function IlinkSettings() {
  const { t } = useTranslation();
  const { phase, session, device, pushReady, logout, push } = useIlinkSession();
  const [pushBusy, setPushBusy] = useState(false);
  const [pushResult, setPushResult] = useState<string | null>(null);

  const runPushTest = useCallback(async () => {
    setPushBusy(true);
    setPushResult(null);
    try {
      const result = await push(t('login.ilink.pushSample'));
      setPushResult(`ok=${result.ok} status=${result.status} ${result.body.slice(0, 200)}`);
      if (result.ok) Message.success(t('login.ilink.pushOk'));
      else Message.warning(`${t('login.ilink.pushFailed')}: ${result.body.slice(0, 160)}`);
    } catch (e) {
      const detail = e instanceof Error ? e.message : String(e);
      setPushResult(detail);
      Message.error(`${t('login.ilink.pushFailed')}: ${detail}`);
    } finally {
      setPushBusy(false);
    }
  }, [push, t]);

  const signOut = useCallback(() => {
    Modal.confirm({
      title: t('login.ilink.signOut'),
      content: t('login.ilink.signOutAsk'),
      okText: t('login.ilink.signOut'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        await logout();
        Message.success(t('login.ilink.signOutDone'));
      },
    });
  }, [logout, t]);

  return (
    <div className='px-4px py-8px' style={{ maxWidth: 720, margin: '0 auto' }} data-testid='ilink-settings'>
      <Title heading={3}>{t('settings.ilink.title')}</Title>

      {session ? (
        <div className='flex flex-col gap-12px py-16px'>
          <Paragraph className='mb-0'>
            <Text bold>{t('settings.ilink.status')}: </Text>
            <Text type='success'>{t('login.ilink.signedIn')}</Text>
          </Paragraph>
          <Paragraph className='mb-0'>
            <Text bold>{t('login.ilink.ilinkId')}: </Text>
            <Text copyable>{session.ilink}</Text>
          </Paragraph>
          {device ? (
            <Paragraph className='mb-0'>
              <Text bold>{t('login.ilink.deviceId')}: </Text>
              <Text copyable>{device.deviceId}</Text>
              <Text type='secondary'>
                {' '}
                ({t('settings.ilink.confidence')}: {device.confidence.toFixed(2)} / {t('settings.ilink.matched')}:{' '}
                {device.matched})
              </Text>
            </Paragraph>
          ) : session.deviceId ? (
            <Paragraph className='mb-0'>
              <Text bold>{t('login.ilink.deviceId')}: </Text>
              <Text copyable>{session.deviceId}</Text>
            </Paragraph>
          ) : null}

          <div className='mt-12px flex flex-col items-start gap-8px'>
            <Text type={pushReady ? 'success' : 'warning'}>
              {pushReady ? t('login.ilink.pushReady') : t('login.ilink.pushNotReady')}
            </Text>
            <Button type='primary' loading={pushBusy} onClick={() => void runPushTest()}>
              {t('login.ilink.pushTest')}
            </Button>
            {pushResult ? (
              <Text type='secondary' style={{ fontSize: 12 }}>
                {pushResult}
              </Text>
            ) : null}
            <Text type='secondary' style={{ fontSize: 12 }}>
              {t('settings.ilink.pushHint')}
            </Text>
          </div>

          <div className='mt-12px'>
            <Button status='danger' onClick={() => signOut()}>
              {t('login.ilink.signOut')}
            </Button>
          </div>
        </div>
      ) : (
        <div className='py-24px'>{phase === 'loading' ? null : <IlinkQrLogin size={220} />}</div>
      )}
    </div>
  );
}
