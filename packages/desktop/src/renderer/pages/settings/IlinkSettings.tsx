import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Message, Spin, Typography } from '@arco-design/web-react';
import { QRCodeSVG } from 'qrcode.react';
import {
  ilinkQrCreate,
  ilinkStatusPoll,
  ilinkDeviceGet,
  ilinkDeviceBind,
  ilinkBotInit,
  ilinkBotSend,
  type ConfirmedLogin,
  type DeviceIdResult,
  type LoginQr,
  type ScanStatus,
} from '@renderer/services/ilink';

const { Paragraph, Text, Title } = Typography;

const POLL_INTERVAL_MS = 1_500;
const APP = 'tupai';

export default function IlinkSettings() {
  const { t } = useTranslation();
  const [qr, setQr] = useState<LoginQr | null>(null);
  const [status, setStatus] = useState<ScanStatus | null>(null);
  const [confirmed, setConfirmed] = useState<ConfirmedLogin | null>(null);
  const [device, setDevice] = useState<DeviceIdResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [push, setPush] = useState<{ result: string } | null>(null);
  const [pushBusy, setPushBusy] = useState(false);
  const activeRef = useRef(false);

  const startScan = useCallback(async () => {
    setConfirmed(null);
    setDevice(null);
    setStatus(null);
    setPush(null);
    setBusy(true);
    try {
      const created = await ilinkQrCreate({ app: APP });
      setQr(created);
      activeRef.current = true;
      const loop = async () => {
        while (activeRef.current) {
          try {
            const s = await ilinkStatusPoll(created.ticket);
            setStatus(s);
            if (s.status !== 'pending') {
              activeRef.current = false;
              if (s.confirmed) {
                setConfirmed(s.confirmed);
                await resolveDevice(s.confirmed);
              }
              return;
            }
          } catch {
            // transient network error: keep polling
          }
          await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
        }
      };
      void loop();
    } catch (e) {
      Message.error(`${t('settings.ilink.error')}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(false);
    }
  }, [t]);

  const resolveDevice = useCallback(async (c: ConfirmedLogin) => {
    try {
      const d = await ilinkDeviceGet({ app: APP });
      setDevice(d);
      if (d.deviceId && c.ilink) {
        const ok = await ilinkDeviceBind({ deviceId: d.deviceId, ilink: c.ilink });
        if (!ok) Message.warning(t('settings.ilink.bindFailed'));
      }
    } catch {
      // device resolution is best-effort; login still succeeded
    }
  }, [t]);

  const runPushTest = useCallback(async () => {
    if (!confirmed) return;
    setPushBusy(true);
    setPush(null);
    try {
      await ilinkBotInit({
        baseUrl: confirmed.baseUrl,
        botToken: confirmed.botToken,
        linkToken: confirmed.linkToken,
        ilink: confirmed.ilink,
        userId: confirmed.userId,
      });
      const res = await ilinkBotSend({ text: t('settings.ilink.pushSample'), toUser: confirmed.ilink });
      setPush({ result: `ok=${res.ok} status=${res.status} ${res.body.slice(0, 200)}` });
      if (res.ok) Message.success(t('settings.ilink.pushOk'));
      else Message.warning(`${t('settings.ilink.pushWarn')}: ${res.body.slice(0, 160)}`);
    } catch (e) {
      setPush({ result: e instanceof Error ? e.message : String(e) });
      Message.error(`${t('settings.ilink.pushErr')}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setPushBusy(false);
    }
  }, [confirmed, t]);

  useEffect(() => {
    void startScan();
    return () => {
      activeRef.current = false;
    };
  }, []);

  return (
    <div style={{ maxWidth: 720, margin: '0 auto', padding: '8px 4px' }}>
      <Title heading={3}>{t('settings.ilink.title')}</Title>

      {!confirmed && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16, padding: '24px 0' }}>
          <Text type='secondary'>{t('settings.ilink.scanHint')}</Text>
          {qr ? (
            <div style={{ padding: 16, background: '#fff', borderRadius: 8 }}>
              <QRCodeSVG value={qr.content} size={220} level='M' />
            </div>
          ) : (
            <Spin />
          )}
          {status && status.status === 'pending' && (
            <Text type='secondary'>{t('settings.ilink.waiting')}</Text>
          )}
          <Button loading={busy} onClick={() => void startScan()}>
            {t('settings.ilink.refresh')}
          </Button>
        </div>
      )}

      {confirmed && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '16px 0' }}>
          <Paragraph>
            <Text bold>{t('settings.ilink.status')}: </Text>
            <Text type='success'>{t('settings.ilink.loggedIn')}</Text>
          </Paragraph>
          <Paragraph>
            <Text bold>iLink ID: </Text>
            <Text copyable>{confirmed.ilink}</Text>
          </Paragraph>
          {device && (
            <Paragraph>
              <Text bold>{t('settings.ilink.deviceId')}: </Text>
              <Text copyable>{device.deviceId}</Text>
              <Text type='secondary'>
                {' '}
                ({t('settings.ilink.confidence')}: {device.confidence.toFixed(2)} / {t('settings.ilink.matched')}:{' '}
                {device.matched})
              </Text>
            </Paragraph>
          )}

          <div style={{ marginTop: 12 }}>
            <Button type='primary' loading={pushBusy} onClick={() => void runPushTest()}>
              {t('settings.ilink.pushTest')}
            </Button>
            {push && (
              <Paragraph style={{ marginTop: 8 }}>
                <Text type='secondary'>{push.result}</Text>
              </Paragraph>
            )}
            <Paragraph style={{ marginTop: 4 }}>
              <Text type='secondary'>{t('settings.ilink.pushHint')}</Text>
            </Paragraph>
          </div>
        </div>
      )}
    </div>
  );
}
